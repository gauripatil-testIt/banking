package com.techverito.banking.service;

import com.techverito.banking.dto.CustomerRequest;
import com.techverito.banking.dto.CustomerResponse;
import com.techverito.banking.entity.Customer;
import com.techverito.banking.event.EventType;
import com.techverito.banking.event.outbox.BusinessEventOutboxRecorder;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@Transactional
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final BusinessEventOutboxRecorder outboxRecorder;

    public CustomerService(CustomerRepository customerRepository,
                           BusinessEventOutboxRecorder outboxRecorder) {
        this.customerRepository = customerRepository;
        this.outboxRecorder = outboxRecorder;
    }

    public CustomerResponse create(CustomerRequest req) {
        Customer customer = Customer.builder()
                .firstName(req.firstName())
                .lastName(req.lastName())
                .email(req.email())
                .phone(req.phone())
                .status(req.status())
                .createdAt(Instant.now())
                .build();
        Customer saved = customerRepository.save(customer);
        // Recorded in the same transaction as the customer, so the event is published
        // only once the registration has committed.
        outboxRecorder.record(EventType.CUSTOMER_REGISTERED, saved.getId(), null);
        return CustomerResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public CustomerResponse getById(Long id) {
        return CustomerResponse.from(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<CustomerResponse> getAll() {
        return customerRepository.findAll().stream()
                .map(CustomerResponse::from)
                .toList();
    }

    public CustomerResponse update(Long id, CustomerRequest req) {
        Customer customer = findOrThrow(id);
        customer.setFirstName(req.firstName());
        customer.setLastName(req.lastName());
        customer.setEmail(req.email());
        customer.setPhone(req.phone());
        customer.setStatus(req.status());
        return CustomerResponse.from(customerRepository.save(customer));
    }

    public void delete(Long id) {
        findOrThrow(id);
        customerRepository.deleteById(id);
    }

    private Customer findOrThrow(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", id));
    }
}

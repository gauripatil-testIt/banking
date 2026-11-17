package com.techverito.banking.service;

import com.techverito.banking.dto.CustomerRequest;
import com.techverito.banking.dto.CustomerResponse;
import com.techverito.banking.entity.Customer;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class CustomerService {

    /**
     * BANK-8 has no authentication, so a customer registered through the API is announced
     * with this cause: it is not a named operator who asked for the welcome email.
     */
    private static final String REQUESTED_BY_SYSTEM = "system";

    private final CustomerRepository customerRepository;
    private final TaskSubmissionService taskSubmissionService;

    public CustomerService(CustomerRepository customerRepository,
                           TaskSubmissionService taskSubmissionService) {
        this.customerRepository = customerRepository;
        this.taskSubmissionService = taskSubmissionService;
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
        // The welcome email is slow work: it is queued in this same transaction, so a
        // customer that was not created never gets an email queued for it.
        taskSubmissionService.submitWelcomeEmail(saved.getId(), saved.getEmail(), REQUESTED_BY_SYSTEM,
                UUID.randomUUID().toString());
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

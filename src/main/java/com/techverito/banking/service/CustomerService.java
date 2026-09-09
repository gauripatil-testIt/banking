package com.techverito.banking.service;

import com.techverito.banking.dto.CustomerRequest;
import com.techverito.banking.dto.CustomerResponse;
import com.techverito.banking.entity.Customer;
import com.techverito.banking.exception.NoAvailableRelationshipManagerException;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.CustomerRepository;
import com.techverito.banking.service.RelationshipManagerAssignmentService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class CustomerService {

    private final CustomerRepository customerRepository;
    private final RelationshipManagerAssignmentService relationshipManagerAssignmentService;

    public CustomerService(CustomerRepository customerRepository, RelationshipManagerAssignmentService relationshipManagerAssignmentService) {
        this.customerRepository = customerRepository;
        this.relationshipManagerAssignmentService = relationshipManagerAssignmentService;
    }

    public CustomerResponse create(CustomerRequest req) {
        Customer customer = Customer.builder()
                .firstName(req.firstName())
                .lastName(req.lastName())
                .email(req.email())
                .phone(req.phone())
                .status(req.status())
                .idNumber(req.idNumber())
                .idType(req.idType())
                .dateOfBirth(req.dateOfBirth())
                .build();

        Long relationshipManagerId = relationshipManagerAssignmentService.assignNext();
        customer.setRelationshipManagerId(relationshipManagerId);

        return CustomerResponse.from(customerRepository.save(customer));
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

        if (req.idNumber() != null) {
            customer.setIdNumber(req.idNumber());
        }
        if (req.idType() != null) {
            customer.setIdType(req.idType());
        }
        if (req.dateOfBirth() != null) {
            customer.setDateOfBirth(req.dateOfBirth());
        }

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

package com.techverito.banking.controller;

import com.techverito.banking.dto.CustomerRequest;
import com.techverito.banking.dto.CustomerResponse;
import com.techverito.banking.service.CustomerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/customers")
public class CustomerController {

    private final CustomerService customerService;

    public CustomerController(CustomerService customerService) {
        this.customerService = customerService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerResponse create(@Valid @RequestBody CustomerRequest request) {
        validateKyc(request, true);
        return customerService.create(request);
    }

    @GetMapping("/{id}")
    public CustomerResponse getById(@PathVariable Long id) {
        return customerService.getById(id);
    }

    @GetMapping
    public List<CustomerResponse> list() {
        return customerService.getAll();
    }

    @PutMapping("/{id}")
    public CustomerResponse update(@PathVariable Long id, @Valid @RequestBody CustomerRequest request) {
        validateKyc(request, false);
        return customerService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        customerService.delete(id);
    }

    private static final String ID_TYPE_PASSPORT = "PASSPORT";
    private static final String ID_TYPE_DRIVER_LICENSE = "DRIVER_LICENSE";
    private static final String ID_TYPE_NATIONAL_ID = "NATIONAL_ID";

    private void validateKyc(CustomerRequest request, boolean requireAllOnCreate) {
        if (requireAllOnCreate) {
            if (request.idNumber() == null || request.idNumber().isBlank()) {
                throw new IllegalArgumentException("idNumber is required");
            }
            if (request.idType() == null || request.idType().isBlank()) {
                throw new IllegalArgumentException("idType is required");
            }
            if (request.dateOfBirth() == null) {
                throw new IllegalArgumentException("dateOfBirth is required");
            }
        } else {
            if (request.idNumber() != null && request.idNumber().isBlank()) {
                throw new IllegalArgumentException("idNumber must not be blank");
            }
            if (request.idType() != null && request.idType().isBlank()) {
                throw new IllegalArgumentException("idType must not be blank");
            }
        }

        if (request.idType() != null) {
            if (!ID_TYPE_PASSPORT.equals(request.idType())
                    && !ID_TYPE_DRIVER_LICENSE.equals(request.idType())
                    && !ID_TYPE_NATIONAL_ID.equals(request.idType())) {
                throw new IllegalArgumentException("idType must be one of [PASSPORT, DRIVER_LICENSE, NATIONAL_ID]");
            }
        }
    }
}

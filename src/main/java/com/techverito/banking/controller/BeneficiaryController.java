package com.techverito.banking.controller;

import com.techverito.banking.dto.BeneficiaryRequest;
import com.techverito.banking.dto.BeneficiaryResponse;
import com.techverito.banking.service.BeneficiaryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class BeneficiaryController {

    private final BeneficiaryService beneficiaryService;

    public BeneficiaryController(BeneficiaryService beneficiaryService) {
        this.beneficiaryService = beneficiaryService;
    }

    @PostMapping("/beneficiaries")
    @ResponseStatus(HttpStatus.CREATED)
    public BeneficiaryResponse create(@Valid @RequestBody BeneficiaryRequest request) {
        return beneficiaryService.create(request);
    }

    @GetMapping("/beneficiaries/{id}")
    public BeneficiaryResponse getById(@PathVariable Long id) {
        return beneficiaryService.getById(id);
    }

    @GetMapping("/customers/{customerId}/beneficiaries")
    public List<BeneficiaryResponse> list(@PathVariable Long customerId) {
        return beneficiaryService.list(customerId);
    }

    @PutMapping("/beneficiaries/{id}")
    public BeneficiaryResponse update(@PathVariable Long id, @Valid @RequestBody BeneficiaryRequest request) {
        return beneficiaryService.update(id, request);
    }

    @DeleteMapping("/beneficiaries/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        beneficiaryService.delete(id);
    }
}

package com.techverito.banking.service;

import com.techverito.banking.dto.BeneficiaryRequest;
import com.techverito.banking.dto.BeneficiaryResponse;
import com.techverito.banking.entity.Beneficiary;
import com.techverito.banking.entity.Customer;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.BeneficiaryRepository;
import com.techverito.banking.repository.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class BeneficiaryService {

    private final BeneficiaryRepository beneficiaryRepository;
    private final CustomerRepository customerRepository;

    public BeneficiaryService(BeneficiaryRepository beneficiaryRepository, CustomerRepository customerRepository) {
        this.beneficiaryRepository = beneficiaryRepository;
        this.customerRepository = customerRepository;
    }

    public BeneficiaryResponse create(BeneficiaryRequest req) {
        Customer customer = customerRepository.findById(req.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer", req.customerId()));
        Beneficiary beneficiary = Beneficiary.builder()
                .customer(customer)
                .name(req.name())
                .accountNumber(req.accountNumber())
                .bankName(req.bankName())
                .beneficiaryType(req.beneficiaryType())
                .build();
        return BeneficiaryResponse.from(beneficiaryRepository.save(beneficiary));
    }

    @Transactional(readOnly = true)
    public BeneficiaryResponse getById(Long id) {
        return BeneficiaryResponse.from(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<BeneficiaryResponse> list(Long customerId) {
        customerRepository.findById(customerId)
                .orElseThrow(() -> new ResourceNotFoundException("Customer", customerId));
        return beneficiaryRepository.findByCustomer_Id(customerId).stream()
                .map(BeneficiaryResponse::from)
                .toList();
    }

    public BeneficiaryResponse update(Long id, BeneficiaryRequest req) {
        Beneficiary beneficiary = findOrThrow(id);
        beneficiary.setName(req.name());
        beneficiary.setAccountNumber(req.accountNumber());
        beneficiary.setBankName(req.bankName());
        beneficiary.setBeneficiaryType(req.beneficiaryType());
        return BeneficiaryResponse.from(beneficiaryRepository.save(beneficiary));
    }

    public void delete(Long id) {
        findOrThrow(id);
        beneficiaryRepository.deleteById(id);
    }

    private Beneficiary findOrThrow(Long id) {
        return beneficiaryRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Beneficiary", id));
    }
}

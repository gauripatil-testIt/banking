package com.techverito.banking.dto;

import com.techverito.banking.entity.Beneficiary;

public record BeneficiaryResponse(
        Long id,
        Long customerId,
        String name,
        String accountNumber,
        String bankName,
        String beneficiaryType
) {
    public static BeneficiaryResponse from(Beneficiary b) {
        return new BeneficiaryResponse(
                b.getId(), b.getCustomer().getId(), b.getName(),
                b.getAccountNumber(), b.getBankName(), b.getBeneficiaryType()
        );
    }
}

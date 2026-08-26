package com.techverito.banking.service;

import com.techverito.banking.dto.BeneficiaryRequest;
import com.techverito.banking.dto.BeneficiaryResponse;
import com.techverito.banking.entity.Beneficiary;
import com.techverito.banking.entity.Customer;
import com.techverito.banking.entity.CustomerStatus;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.BeneficiaryRepository;
import com.techverito.banking.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BeneficiaryServiceTest {

    @Mock
    BeneficiaryRepository beneficiaryRepository;

    @Mock
    CustomerRepository customerRepository;

    @InjectMocks
    BeneficiaryService beneficiaryService;

    private Customer customer(Long id) {
        return Customer.builder()
                .id(id).firstName("John").lastName("Doe")
                .email("john@example.com").phone("123")
                .status(CustomerStatus.ACTIVE).build();
    }

    private Beneficiary beneficiary(Long id, Customer customer) {
        return Beneficiary.builder()
                .id(id).customer(customer)
                .name("Jane Payee").accountNumber("ACC999")
                .bankName("First Bank").beneficiaryType("INDIVIDUAL")
                .build();
    }

    private BeneficiaryRequest request() {
        return new BeneficiaryRequest(1L, "Jane Payee", "ACC999", "First Bank", "INDIVIDUAL");
    }

    @Test
    void create_validCustomer_savesAndReturnsResponse() {
        Customer c = customer(1L);
        Beneficiary b = beneficiary(1L, c);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(c));
        when(beneficiaryRepository.save(any())).thenReturn(b);

        BeneficiaryResponse res = beneficiaryService.create(request());

        assertThat(res.id()).isEqualTo(1L);
        assertThat(res.customerId()).isEqualTo(1L);
        assertThat(res.name()).isEqualTo("Jane Payee");
        assertThat(res.accountNumber()).isEqualTo("ACC999");
        assertThat(res.bankName()).isEqualTo("First Bank");
        assertThat(res.beneficiaryType()).isEqualTo("INDIVIDUAL");
    }

    @Test
    void create_customerNotFound_throwsException() {
        when(customerRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> beneficiaryService.create(request()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Customer");
    }

    @Test
    void getById_found_returnsResponse() {
        Beneficiary b = beneficiary(1L, customer(1L));
        when(beneficiaryRepository.findById(1L)).thenReturn(Optional.of(b));

        BeneficiaryResponse res = beneficiaryService.getById(1L);

        assertThat(res.accountNumber()).isEqualTo("ACC999");
        assertThat(res.bankName()).isEqualTo("First Bank");
    }

    @Test
    void getById_notFound_throwsException() {
        when(beneficiaryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> beneficiaryService.getById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Beneficiary");
    }

    @Test
    void list_withCustomerId_returnsFiltered() {
        Customer c = customer(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(c));
        when(beneficiaryRepository.findByCustomer_Id(1L)).thenReturn(List.of(beneficiary(1L, c)));

        List<BeneficiaryResponse> res = beneficiaryService.list(1L);

        assertThat(res).hasSize(1);
        assertThat(res.get(0).customerId()).isEqualTo(1L);
        verify(beneficiaryRepository).findByCustomer_Id(1L);
    }

    @Test
    void list_customerNotFound_throwsException() {
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> beneficiaryService.list(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Customer");

        verify(beneficiaryRepository, never()).findByCustomer_Id(any());
    }

    @Test
    void update_found_updatesAndReturns() {
        Customer c = customer(1L);
        Beneficiary existing = beneficiary(1L, c);
        when(beneficiaryRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(beneficiaryRepository.save(any())).thenReturn(existing);

        BeneficiaryRequest updateReq = new BeneficiaryRequest(1L, "Updated Name", "ACC000", "Second Bank", "BUSINESS");

        BeneficiaryResponse res = beneficiaryService.update(1L, updateReq);

        assertThat(res).isNotNull();
        assertThat(res.name()).isEqualTo("Updated Name");
        assertThat(res.accountNumber()).isEqualTo("ACC000");
        assertThat(res.bankName()).isEqualTo("Second Bank");
        assertThat(res.beneficiaryType()).isEqualTo("BUSINESS");
        verify(beneficiaryRepository).save(existing);
    }

    @Test
    void update_notFound_throwsException() {
        when(beneficiaryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> beneficiaryService.update(99L, request()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Beneficiary");
    }

    @Test
    void delete_found_deletesById() {
        Beneficiary b = beneficiary(1L, customer(1L));
        when(beneficiaryRepository.findById(1L)).thenReturn(Optional.of(b));

        beneficiaryService.delete(1L);

        verify(beneficiaryRepository).deleteById(1L);
    }

    @Test
    void delete_notFound_throwsException() {
        when(beneficiaryRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> beneficiaryService.delete(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}

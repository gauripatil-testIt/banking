package com.techverito.banking.service;

import com.techverito.banking.dto.CustomerRequest;
import com.techverito.banking.dto.CustomerResponse;
import com.techverito.banking.entity.Customer;
import com.techverito.banking.entity.CustomerStatus;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.CustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {

    @Mock
    CustomerRepository customerRepository;

    @Mock
    RelationshipManagerAssignmentService relationshipManagerAssignmentService;

    @InjectMocks
    CustomerService customerService;

    private CustomerRequest request() {
        return new CustomerRequest("John", "Doe", "john@example.com", "1234567890", CustomerStatus.ACTIVE,
                "ID12345", "PASSPORT", LocalDate.of(1990, 1, 1));
    }

    private Customer customer(Long id) {
        return Customer.builder()
                .id(id).firstName("John").lastName("Doe")
                .email("john@example.com").phone("1234567890")
                .status(CustomerStatus.ACTIVE)
                .idNumber("ID12345")
                .idType("PASSPORT")
                .dateOfBirth(LocalDate.of(1990, 1, 1))
                .relationshipManagerId(99L)
                .build();
    }

    @Test
    void create_savesAndReturnsResponse() {
        Customer saved = customer(1L);
        when(customerRepository.save(any())).thenReturn(saved);
        when(relationshipManagerAssignmentService.assignNext()).thenReturn(99L);

        CustomerResponse res = customerService.create(request());

        assertThat(res.id()).isEqualTo(1L);
        assertThat(res.firstName()).isEqualTo("John");
        assertThat(res.status()).isEqualTo(CustomerStatus.ACTIVE);
        assertThat(res.idNumber()).isEqualTo("ID12345");
        assertThat(res.idType()).isEqualTo("PASSPORT");
        assertThat(res.dateOfBirth()).isEqualTo(LocalDate.of(1990, 1, 1));
        assertThat(res.relationshipManagerId()).isEqualTo(99L);
        verify(customerRepository).save(any(Customer.class));
    }

    @Test
    void create_noAvailableRelationshipManager_throwsException() {
        when(relationshipManagerAssignmentService.assignNext()).thenThrow(new com.techverito.banking.exception.NoAvailableRelationshipManagerException());

        assertThatThrownBy(() -> customerService.create(request()))
                .isInstanceOf(com.techverito.banking.exception.NoAvailableRelationshipManagerException.class);

        verify(customerRepository, never()).save(any(Customer.class));
    }

    @Test
    void getById_found_returnsResponse() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));

        CustomerResponse res = customerService.getById(1L);

        assertThat(res.email()).isEqualTo("john@example.com");
        assertThat(res.idNumber()).isEqualTo("ID12345");
    }

    @Test
    void getById_notFound_throwsException() {
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.getById(99L))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("99");
    }

    @Test
    void getAll_returnsAll() {
        when(customerRepository.findAll()).thenReturn(List.of(customer(1L), customer(2L)));

        List<CustomerResponse> res = customerService.getAll();

        assertThat(res).hasSize(2);
    }

    @Test
    void update_found_updatesFields() {
        Customer existing = customer(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(customerRepository.save(any())).thenReturn(existing);

        CustomerRequest updateReq = new CustomerRequest("Jane", "Smith", "jane@example.com", "999", CustomerStatus.INACTIVE,
                "NEWID", "NATIONAL_ID", LocalDate.of(1985, 5, 20));

        CustomerResponse res = customerService.update(1L, updateReq);

        assertThat(res).isNotNull();
        assertThat(res.idNumber()).isEqualTo("NEWID");
        verify(customerRepository).save(existing);
    }

    @Test
    void update_notFound_throwsException() {
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.update(99L, request()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void update_nullKycFields_retainsExisting() {
        Customer existing = customer(1L);
        when(customerRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(customerRepository.save(any())).thenReturn(existing);

        CustomerRequest updateReq = new CustomerRequest("Jane", "Smith", "jane@example.com", "999", CustomerStatus.INACTIVE,
                null, null, null);

        CustomerResponse res = customerService.update(1L, updateReq);

        assertThat(res.idNumber()).isEqualTo("ID12345");
        assertThat(res.idType()).isEqualTo("PASSPORT");
        assertThat(res.dateOfBirth()).isEqualTo(LocalDate.of(1990, 1, 1));
        verify(customerRepository).save(existing);
    }

    @Test
    void delete_found_deletesById() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));

        customerService.delete(1L);

        verify(customerRepository).deleteById(1L);
    }

    @Test
    void delete_notFound_throwsException() {
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.delete(99L))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}

package com.techverito.banking.service;

import com.techverito.banking.dto.CustomerRequest;
import com.techverito.banking.dto.CustomerResponse;
import com.techverito.banking.entity.Customer;
import com.techverito.banking.entity.CustomerStatus;
import com.techverito.banking.entity.IdType;
import com.techverito.banking.exception.InvalidCustomerRequestException;
import com.techverito.banking.exception.ResourceNotFoundException;
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
class CustomerServiceTest {

    @Mock
    CustomerRepository customerRepository;

    @Mock
    RelationshipManagerAssignmentService relationshipManagerAssignmentService;

    @InjectMocks
    CustomerService customerService;

    private CustomerRequest request() {
        return new CustomerRequest("John", "Doe", "john@example.com", "1234567890", CustomerStatus.ACTIVE,
                "ID123456", IdType.NID, null, null);
    }

    private Customer customer(Long id) {
        return Customer.builder()
                .id(id).firstName("John").lastName("Doe")
                .email("john@example.com").phone("1234567890")
                .status(CustomerStatus.ACTIVE)
                .idNumber("ID123456").idType(IdType.NID).build();
    }

    @Test
    void create_savesAndReturnsResponse() {
        Customer saved = customer(1L);
        when(relationshipManagerAssignmentService.assignNext()).thenReturn(null);
        when(customerRepository.save(any())).thenReturn(saved);

        CustomerResponse res = customerService.create(request());

        assertThat(res.id()).isEqualTo(1L);
        assertThat(res.firstName()).isEqualTo("John");
        assertThat(res.status()).isEqualTo(CustomerStatus.ACTIVE);
        verify(customerRepository).save(any(Customer.class));
    }

    @Test
    void create_assignsRelationshipManagerFromAssignmentService() {
        Customer saved = Customer.builder()
                .id(1L).firstName("John").lastName("Doe")
                .email("john@example.com").phone("1234567890")
                .status(CustomerStatus.ACTIVE)
                .idNumber("ID123456").idType(IdType.NID)
                .relationshipManagerId(7L).build();
        when(relationshipManagerAssignmentService.assignNext()).thenReturn(7L);
        when(customerRepository.save(any())).thenReturn(saved);

        CustomerResponse res = customerService.create(request());

        assertThat(res.relationshipManagerId()).isEqualTo(7L);
        verify(customerRepository).save(argThat(c -> java.util.Objects.equals(c.getRelationshipManagerId(), 7L)));
    }

    @Test
    void create_noActiveRelationshipManagers_assignsNull() {
        Customer saved = customer(1L);
        when(relationshipManagerAssignmentService.assignNext()).thenReturn(null);
        when(customerRepository.save(any())).thenReturn(saved);

        CustomerResponse res = customerService.create(request());

        assertThat(res.relationshipManagerId()).isNull();
        verify(customerRepository).save(argThat(c -> c.getRelationshipManagerId() == null));
    }

    @Test
    void create_requestSuppliesRelationshipManagerId_throwsException() {
        CustomerRequest req = new CustomerRequest("John", "Doe", "john@example.com", "1234567890",
                CustomerStatus.ACTIVE, "ID123456", IdType.NID, null, 5L);

        assertThatThrownBy(() -> customerService.create(req))
                .isInstanceOf(InvalidCustomerRequestException.class);

        verifyNoInteractions(customerRepository);
        verifyNoInteractions(relationshipManagerAssignmentService);
    }

    @Test
    void getById_found_returnsResponse() {
        when(customerRepository.findById(1L)).thenReturn(Optional.of(customer(1L)));

        CustomerResponse res = customerService.getById(1L);

        assertThat(res.email()).isEqualTo("john@example.com");
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

        CustomerResponse res = customerService.update(1L,
                new CustomerRequest("Jane", "Smith", "jane@example.com", "999", CustomerStatus.INACTIVE,
                        "ID999999", IdType.PASSPORT, null, null));

        assertThat(res).isNotNull();
        verify(customerRepository).save(existing);
    }

    @Test
    void update_notFound_throwsException() {
        when(customerRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> customerService.update(99L, request()))
                .isInstanceOf(ResourceNotFoundException.class);
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

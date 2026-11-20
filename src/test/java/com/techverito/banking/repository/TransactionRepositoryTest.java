package com.techverito.banking.repository;

import com.techverito.banking.entity.Customer;
import com.techverito.banking.entity.CustomerStatus;
import com.techverito.banking.entity.Transaction;
import com.techverito.banking.entity.TransactionStatus;
import com.techverito.banking.entity.TransactionType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The filtered transaction listing query against the database: each filter is optional, and any
 * combination of status, type and customer id returns exactly the matching rows, with the customer
 * loaded in the same query.
 */
@DataJpaTest
class TransactionRepositoryTest {

    private static final Instant OCCURRED_AT = Instant.parse("2025-03-04T09:07:45Z");

    @Autowired
    TransactionRepository transactionRepository;

    @Autowired
    CustomerRepository customerRepository;

    @Autowired
    TestEntityManager entityManager;

    private Customer customer(String email) {
        return customerRepository.save(Customer.builder()
                .firstName("John").lastName("Doe")
                .email(email).phone("123")
                .status(CustomerStatus.ACTIVE)
                .createdAt(OCCURRED_AT)
                .build());
    }

    private Transaction transaction(Customer customer, TransactionStatus status,
                                    TransactionType type, String amount) {
        return transactionRepository.save(Transaction.builder()
                .customer(customer)
                .status(status)
                .type(type)
                .amount(new BigDecimal(amount))
                .occurredAt(OCCURRED_AT)
                .build());
    }

    private void flushAndClear() {
        transactionRepository.flush();
        entityManager.clear();
    }

    private static List<Long> ids(List<Transaction> rows) {
        return rows.stream().map(Transaction::getId).toList();
    }

    @Test
    void noFiltersReturnsAll() {
        Customer first = customer("first@example.com");
        Customer second = customer("second@example.com");
        Transaction deposit = transaction(first, TransactionStatus.COMPLETED, TransactionType.DEPOSIT, "100.00");
        Transaction withdrawal = transaction(second, TransactionStatus.PENDING, TransactionType.WITHDRAWAL, "50.00");
        Transaction transfer = transaction(first, TransactionStatus.FAILED, TransactionType.TRANSFER, "25.00");
        flushAndClear();

        List<Transaction> all = transactionRepository.findFiltered(null, null, null);

        assertThat(ids(all)).containsExactlyInAnyOrder(deposit.getId(), withdrawal.getId(), transfer.getId());
    }

    @Test
    void filtersByStatusOnly() {
        Customer first = customer("status-first@example.com");
        Customer second = customer("status-second@example.com");
        Transaction completed = transaction(first, TransactionStatus.COMPLETED, TransactionType.DEPOSIT, "10.00");
        Transaction pending = transaction(second, TransactionStatus.PENDING, TransactionType.DEPOSIT, "20.00");
        Transaction failed = transaction(first, TransactionStatus.FAILED, TransactionType.DEPOSIT, "30.00");
        flushAndClear();

        List<Transaction> completedRows =
                transactionRepository.findFiltered(TransactionStatus.COMPLETED, null, null);

        assertThat(ids(completedRows)).containsExactly(completed.getId());

        List<Transaction> pendingRows = transactionRepository.findFiltered(TransactionStatus.PENDING, null, null);
        assertThat(ids(pendingRows)).containsExactly(pending.getId());
    }

    @Test
    void filtersByTypeOnly() {
        Customer first = customer("type-first@example.com");
        Customer second = customer("type-second@example.com");
        Transaction deposit = transaction(first, TransactionStatus.COMPLETED, TransactionType.DEPOSIT, "10.00");
        Transaction withdrawal = transaction(second, TransactionStatus.COMPLETED, TransactionType.WITHDRAWAL, "20.00");
        Transaction transfer = transaction(first, TransactionStatus.COMPLETED, TransactionType.TRANSFER, "30.00");
        flushAndClear();

        List<Transaction> deposits = transactionRepository.findFiltered(null, TransactionType.DEPOSIT, null);

        assertThat(ids(deposits)).containsExactly(deposit.getId());

        List<Transaction> transfers = transactionRepository.findFiltered(null, TransactionType.TRANSFER, null);
        assertThat(ids(transfers)).containsExactly(transfer.getId());
    }

    @Test
    void filtersByCustomerIdOnly() {
        Customer first = customer("customer-first@example.com");
        Customer second = customer("customer-second@example.com");
        Transaction firstDeposit = transaction(first, TransactionStatus.COMPLETED, TransactionType.DEPOSIT, "10.00");
        Transaction firstWithdrawal = transaction(first, TransactionStatus.PENDING, TransactionType.WITHDRAWAL, "20.00");
        Transaction secondDeposit = transaction(second, TransactionStatus.COMPLETED, TransactionType.DEPOSIT, "30.00");
        flushAndClear();

        List<Transaction> firstCustomers =
                transactionRepository.findFiltered(null, null, first.getId());

        assertThat(ids(firstCustomers)).containsExactlyInAnyOrder(firstDeposit.getId(), firstWithdrawal.getId());

        List<Transaction> secondCustomers =
                transactionRepository.findFiltered(null, null, second.getId());
        assertThat(ids(secondCustomers)).containsExactly(secondDeposit.getId());
    }

    @Test
    void filtersByAllThreeCombined() {
        Customer first = customer("combined-first@example.com");
        Customer second = customer("combined-second@example.com");
        Transaction matching = transaction(first, TransactionStatus.COMPLETED, TransactionType.TRANSFER, "12.34");
        transaction(first, TransactionStatus.PENDING, TransactionType.TRANSFER, "20.00");
        transaction(first, TransactionStatus.COMPLETED, TransactionType.DEPOSIT, "30.00");
        transaction(second, TransactionStatus.COMPLETED, TransactionType.TRANSFER, "40.00");
        flushAndClear();

        List<Transaction> rows = transactionRepository.findFiltered(
                TransactionStatus.COMPLETED, TransactionType.TRANSFER, first.getId());

        assertThat(ids(rows)).containsExactly(matching.getId());
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.getCustomer().getId()).isEqualTo(first.getId());
            assertThat(row.getStatus()).isEqualTo(TransactionStatus.COMPLETED);
            assertThat(row.getType()).isEqualTo(TransactionType.TRANSFER);
        });
    }
}

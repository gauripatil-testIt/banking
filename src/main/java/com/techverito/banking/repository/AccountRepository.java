package com.techverito.banking.repository;

import com.techverito.banking.entity.Account;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AccountRepository extends JpaRepository<Account, Long> {
    List<Account> findByCustomer_Id(Long customerId);

    /**
     * Every account with its owning customer loaded in the same query, so the
     * account export does not issue one query per row.
     */
    @Query("select a from Account a join fetch a.customer")
    List<Account> findAllWithCustomer();
}

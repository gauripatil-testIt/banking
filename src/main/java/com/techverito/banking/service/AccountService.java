package com.techverito.banking.service;

import com.techverito.banking.dto.AccountRequest;
import com.techverito.banking.dto.AccountResponse;
import com.techverito.banking.entity.Account;
import com.techverito.banking.entity.Customer;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.AccountRepository;
import com.techverito.banking.repository.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Currency;
import java.util.List;

@Service
@Transactional
public class AccountService {

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;

    public AccountService(AccountRepository accountRepository, CustomerRepository customerRepository) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
    }

    public AccountResponse create(AccountRequest req) {
        Customer customer = customerRepository.findById(req.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer", req.customerId()));
        Currency currency = req.currency() != null ? req.currency() : Currency.getInstance("USD");
        Account account = Account.builder()
                .customer(customer)
                .accountNumber(req.accountNumber())
                .type(req.type())
                .balance(req.balance())
                .status(req.status())
                .currency(currency)
                .build();
        return AccountResponse.from(accountRepository.save(account));
    }

    @Transactional(readOnly = true)
    public AccountResponse getById(Long id) {
        return AccountResponse.from(findOrThrow(id));
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list(Long customerId) {
        List<Account> accounts = (customerId != null)
                ? accountRepository.findByCustomer_Id(customerId)
                : accountRepository.findAll();
        return accounts.stream().map(AccountResponse::from).toList();
    }

    public AccountResponse update(Long id, AccountRequest req) {
        Account account = findOrThrow(id);
        Customer customer = customerRepository.findById(req.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer", req.customerId()));
        Currency currency = req.currency() != null ? req.currency() : Currency.getInstance("USD");
        account.setCustomer(customer);
        account.setAccountNumber(req.accountNumber());
        account.setType(req.type());
        account.setBalance(req.balance());
        account.setStatus(req.status());
        account.setCurrency(currency);
        return AccountResponse.from(accountRepository.save(account));
    }

    public void delete(Long id) {
        findOrThrow(id);
        accountRepository.deleteById(id);
    }

    private Account findOrThrow(Long id) {
        return accountRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Account", id));
    }
}

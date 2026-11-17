package com.techverito.banking.service;

import com.techverito.banking.dto.AccountRequest;
import com.techverito.banking.dto.AccountResponse;
import com.techverito.banking.entity.Account;
import com.techverito.banking.entity.AccountStatus;
import com.techverito.banking.entity.Customer;
import com.techverito.banking.event.AccountOpenedPayload;
import com.techverito.banking.event.AccountStatusChangedPayload;
import com.techverito.banking.event.BusinessEventOutbox;
import com.techverito.banking.event.BusinessEventType;
import com.techverito.banking.event.SubjectType;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.AccountRepository;
import com.techverito.banking.repository.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class AccountService {

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final BusinessEventOutbox businessEventOutbox;

    public AccountService(AccountRepository accountRepository, CustomerRepository customerRepository,
                          BusinessEventOutbox businessEventOutbox) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.businessEventOutbox = businessEventOutbox;
    }

    public AccountResponse create(AccountRequest req) {
        Customer customer = customerRepository.findById(req.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer", req.customerId()));
        Account account = Account.builder()
                .customer(customer)
                .accountNumber(req.accountNumber())
                .type(req.type())
                .balance(req.balance())
                .status(req.status())
                .build();
        Account saved = accountRepository.save(account);
        businessEventOutbox.record(BusinessEventType.ACCOUNT_OPENED, SubjectType.ACCOUNT, saved.getId(),
                BusinessEventOutbox.SYSTEM_CAUSE, UUID.randomUUID().toString(),
                new AccountOpenedPayload(saved.getId(), customer.getId(), saved.getAccountNumber(),
                        saved.getType(), saved.getBalance()));
        return AccountResponse.from(saved);
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
        AccountStatus previousStatus = account.getStatus();
        account.setCustomer(customer);
        account.setAccountNumber(req.accountNumber());
        account.setType(req.type());
        account.setBalance(req.balance());
        account.setStatus(req.status());
        Account saved = accountRepository.save(account);
        if (previousStatus != null && previousStatus != saved.getStatus()) {
            businessEventOutbox.record(BusinessEventType.ACCOUNT_STATUS_CHANGED, SubjectType.ACCOUNT, saved.getId(),
                    BusinessEventOutbox.SYSTEM_CAUSE, UUID.randomUUID().toString(),
                    new AccountStatusChangedPayload(saved.getId(), saved.getAccountNumber(), previousStatus,
                            saved.getStatus()));
        }
        return AccountResponse.from(saved);
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

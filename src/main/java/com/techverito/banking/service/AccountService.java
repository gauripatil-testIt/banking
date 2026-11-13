package com.techverito.banking.service;

import com.techverito.banking.dto.AccountRequest;
import com.techverito.banking.dto.AccountResponse;
import com.techverito.banking.entity.Account;
import com.techverito.banking.entity.AccountStatus;
import com.techverito.banking.entity.Customer;
import com.techverito.banking.event.AccountOpenedPayload;
import com.techverito.banking.event.AccountStatusChangedPayload;
import com.techverito.banking.event.BusinessEvent;
import com.techverito.banking.event.EventBus;
import com.techverito.banking.event.EventRequestContext;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.AccountRepository;
import com.techverito.banking.repository.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

@Service
@Transactional
public class AccountService {

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final EventBus eventBus;
    private final EventRequestContext requestContext;

    public AccountService(AccountRepository accountRepository,
                          CustomerRepository customerRepository,
                          EventBus eventBus,
                          EventRequestContext requestContext) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.eventBus = eventBus;
        this.requestContext = requestContext;
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
        eventBus.publish(BusinessEvent.of(
                new AccountOpenedPayload(saved.getId(), saved.getCustomer().getId(),
                        saved.getAccountNumber(), saved.getType(), saved.getStatus()),
                saved.getCustomer().getId(), saved.getId()), requestContext.actor());
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
        AccountStatus previousStatus = account.getStatus();
        Customer customer = customerRepository.findById(req.customerId())
                .orElseThrow(() -> new ResourceNotFoundException("Customer", req.customerId()));
        account.setCustomer(customer);
        account.setAccountNumber(req.accountNumber());
        account.setType(req.type());
        account.setBalance(req.balance());
        account.setStatus(req.status());
        Account saved = accountRepository.save(account);
        if (!Objects.equals(previousStatus, saved.getStatus())) {
            eventBus.publish(BusinessEvent.of(
                    new AccountStatusChangedPayload(saved.getId(), previousStatus, saved.getStatus()),
                    saved.getCustomer().getId(), saved.getId()), requestContext.actor());
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

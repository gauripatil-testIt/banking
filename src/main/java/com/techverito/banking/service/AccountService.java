package com.techverito.banking.service;

import com.techverito.banking.dto.AccountRequest;
import com.techverito.banking.dto.AccountResponse;
import com.techverito.banking.dto.StatementResponse;
import com.techverito.banking.entity.Account;
import com.techverito.banking.entity.AccountStatus;
import com.techverito.banking.entity.Customer;
import com.techverito.banking.event.EventType;
import com.techverito.banking.event.outbox.BusinessEventOutboxRecorder;
import com.techverito.banking.event.task.BackgroundTaskMessage;
import com.techverito.banking.event.task.BackgroundTaskOutboxRecorder;
import com.techverito.banking.event.task.TaskType;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.repository.AccountRepository;
import com.techverito.banking.repository.CustomerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional
public class AccountService {

    private final AccountRepository accountRepository;
    private final CustomerRepository customerRepository;
    private final BusinessEventOutboxRecorder outboxRecorder;
    private final BackgroundTaskOutboxRecorder backgroundTaskRecorder;

    public AccountService(AccountRepository accountRepository, CustomerRepository customerRepository,
                          BusinessEventOutboxRecorder outboxRecorder,
                          BackgroundTaskOutboxRecorder backgroundTaskRecorder) {
        this.accountRepository = accountRepository;
        this.customerRepository = customerRepository;
        this.outboxRecorder = outboxRecorder;
        this.backgroundTaskRecorder = backgroundTaskRecorder;
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
        // Recorded in the same transaction as the account, so the event is published
        // only once the account has committed.
        outboxRecorder.record(EventType.ACCOUNT_OPENED, saved.getCustomer().getId(), saved.getId());
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
        if (previousStatus != saved.getStatus()) {
            // Keyed by the account, so a status change stays in order with the rest of
            // this account's events.
            outboxRecorder.record(EventType.ACCOUNT_STATUS_CHANGED, saved.getCustomer().getId(), saved.getId());
        }
        return AccountResponse.from(saved);
    }

    /**
     * Asks for a statement of one account: the work itself is a background task, so the
     * request only queues it. The task row is recorded in the same transaction as the
     * lookup, so nothing is queued for an account that does not exist.
     */
    public StatementResponse requestStatement(Long accountId) {
        Account account = findOrThrow(accountId);
        BackgroundTaskMessage task = backgroundTaskRecorder.submit(TaskType.STATEMENT_GENERATION, "account",
                account.getId(), account.getCustomer().getId());
        return StatementResponse.from(account, task);
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

package com.techverito.banking.controller;

import com.techverito.banking.dto.TaskAcceptedResponse;
import com.techverito.banking.service.AccountService;
import com.techverito.banking.service.TaskSubmissionService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Submitting a statement generation request:
 * {@code POST /accounts/{accountId}/statements?periodStart=&periodEnd=&requestedBy=}.
 *
 * <p>The statement itself is produced by a background worker (BANK-11), so this endpoint
 * only queues the work and answers {@code 202 Accepted} with the task id to poll on
 * {@code GET /tasks/{taskId}}. An unknown account is still the application's usual 404
 * ProblemDetail, and no request returns statement content synchronously.</p>
 */
@RestController
@RequestMapping("/accounts/{accountId}/statements")
public class StatementController {

    private final AccountService accountService;
    private final TaskSubmissionService taskSubmissionService;

    public StatementController(AccountService accountService, TaskSubmissionService taskSubmissionService) {
        this.accountService = accountService;
        this.taskSubmissionService = taskSubmissionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TaskAcceptedResponse request(@PathVariable Long accountId,
                                        @RequestParam("periodStart") LocalDate periodStart,
                                        @RequestParam("periodEnd") LocalDate periodEnd,
                                        @RequestParam("requestedBy") String requestedBy) {
        // The account is looked up first, so an unknown account is a 404 and never a task.
        accountService.getById(accountId);
        return taskSubmissionService.submitStatementGeneration(accountId, periodStart, periodEnd, requestedBy,
                UUID.randomUUID().toString());
    }
}

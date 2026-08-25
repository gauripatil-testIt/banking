package com.techverito.banking.controller;

import com.techverito.banking.dto.TransactionRequest;
import com.techverito.banking.dto.TransactionResponse;
import com.techverito.banking.entity.TransactionStatus;
import com.techverito.banking.entity.TransactionType;
import com.techverito.banking.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse create(@Valid @RequestBody TransactionRequest request) {
        return transactionService.create(request);
    }

    @PostMapping("/accounts/{accountId}/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse createNested(@PathVariable Long accountId, @Valid @RequestBody TransactionRequest request) {
        TransactionRequest nestedRequest = new TransactionRequest(
                accountId, request.type(), request.amount(), request.balanceAfter(),
                request.status(), request.targetAccountNumber()
        );
        return transactionService.create(nestedRequest);
    }

    @GetMapping("/transactions/{id}")
    public TransactionResponse getById(@PathVariable Long id) {
        return transactionService.getById(id);
    }

    @GetMapping("/transactions")
    public List<TransactionResponse> list(@RequestParam(required = false) Long accountId,
                                           @RequestParam(required = false) TransactionStatus status,
                                           @RequestParam(required = false) TransactionType type,
                                           @RequestParam(required = false) Long customerId) {
        return transactionService.list(accountId, status, type, customerId);
    }

    @GetMapping("/accounts/{accountId}/transactions")
    public List<TransactionResponse> listNested(@PathVariable Long accountId) {
        return transactionService.list(accountId);
    }

    @PutMapping("/transactions/{id}")
    public TransactionResponse update(@PathVariable Long id, @Valid @RequestBody TransactionRequest request) {
        return transactionService.update(id, request);
    }

    @DeleteMapping("/transactions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        transactionService.delete(id);
    }
}

package com.techverito.banking.controller;

import com.techverito.banking.dto.TransactionRequest;
import com.techverito.banking.dto.TransactionResponse;
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

    @PostMapping("/accounts/{accountId}/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse create(@PathVariable Long accountId, @Valid @RequestBody TransactionRequest request) {
        return transactionService.create(accountId, request);
    }

    @GetMapping("/accounts/{accountId}/transactions")
    public List<TransactionResponse> list(@PathVariable Long accountId) {
        return transactionService.list(accountId);
    }

    @GetMapping("/transactions/{id}")
    public TransactionResponse getById(@PathVariable Long id) {
        return transactionService.getById(id);
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

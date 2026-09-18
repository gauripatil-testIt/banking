package com.techverito.banking.controller;

import com.techverito.banking.dto.TransactionRequest;
import com.techverito.banking.dto.TransactionResponse;
import com.techverito.banking.dto.TransactionStatusUpdateRequest;
import com.techverito.banking.entity.TransactionStatus;
import com.techverito.banking.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/accounts/{accountId}/transactions")
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse create(@PathVariable Long accountId, @Valid @RequestBody TransactionRequest request) {
        return transactionService.create(accountId, request);
    }

    @GetMapping("/{id}")
    public TransactionResponse getById(@PathVariable Long accountId, @PathVariable Long id) {
        return transactionService.getById(accountId, id);
    }

    @GetMapping
    public Page<TransactionResponse> list(@PathVariable Long accountId,
                                           @RequestParam(required = false) TransactionStatus status,
                                           Pageable pageable) {
        return transactionService.list(accountId, status, pageable);
    }

    @PutMapping("/{id}")
    public TransactionResponse updateStatus(@PathVariable Long accountId, @PathVariable Long id,
                                             @Valid @RequestBody TransactionStatusUpdateRequest request) {
        return transactionService.updateStatus(accountId, id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long accountId, @PathVariable Long id) {
        transactionService.delete(accountId, id);
    }
}

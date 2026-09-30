package com.techverito.banking.controller;

import com.techverito.banking.dto.PagedResponse;
import com.techverito.banking.dto.TransactionRequest;
import com.techverito.banking.dto.TransactionResponse;
import com.techverito.banking.dto.TransferRequest;
import com.techverito.banking.dto.TransferResponse;
import com.techverito.banking.entity.TransactionStatus;
import com.techverito.banking.service.TransactionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
public class TransactionController {

    private final TransactionService transactionService;

    public TransactionController(TransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @PostMapping("/accounts/{accountId}/transactions")
    @ResponseStatus(HttpStatus.CREATED)
    public TransactionResponse create(@PathVariable Long accountId, @Valid @RequestBody TransactionRequest request) {
        return transactionService.recordTransaction(accountId, request);
    }

    @PostMapping("/transfers")
    @ResponseStatus(HttpStatus.CREATED)
    public TransferResponse transfer(@Valid @RequestBody TransferRequest request) {
        return transactionService.transfer(request);
    }

    @GetMapping("/accounts/{accountId}/transactions")
    public PagedResponse<TransactionResponse> history(
            @PathVariable Long accountId,
            @RequestParam(required = false) TransactionStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return transactionService.history(accountId, status, page, size);
    }

    @PostMapping("/transactions/{id}/cancel")
    public TransactionResponse cancel(@PathVariable Long id) {
        return transactionService.cancel(id);
    }

    @DeleteMapping("/transactions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        transactionService.delete(id);
    }
}

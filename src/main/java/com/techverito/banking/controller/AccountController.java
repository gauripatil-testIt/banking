package com.techverito.banking.controller;

import com.techverito.banking.dto.AccountRequest;
import com.techverito.banking.dto.AccountResponse;
import com.techverito.banking.service.AccountService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/accounts")
public class AccountController {

    private final AccountService accountService;

    public AccountController(AccountService accountService) {
        this.accountService = accountService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AccountResponse create(@Valid @RequestBody AccountRequest request) {
        return accountService.create(request);
    }

    @GetMapping("/{id}")
    public AccountResponse getById(@PathVariable Long id) {
        return accountService.getById(id);
    }

    @GetMapping
    public List<AccountResponse> list(@RequestParam(required = false) Long customerId) {
        return accountService.list(customerId);
    }

    @PutMapping("/{id}")
    public AccountResponse update(@PathVariable Long id, @Valid @RequestBody AccountRequest request) {
        return accountService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id) {
        accountService.delete(id);
    }
}

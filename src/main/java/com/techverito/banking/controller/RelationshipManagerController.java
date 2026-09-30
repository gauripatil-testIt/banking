package com.techverito.banking.controller;

import com.techverito.banking.dto.RelationshipManagerRequest;
import com.techverito.banking.dto.RelationshipManagerResponse;
import com.techverito.banking.dto.RelationshipManagerStatusRequest;
import com.techverito.banking.service.RelationshipManagerService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/relationship-managers")
public class RelationshipManagerController {

    private final RelationshipManagerService relationshipManagerService;

    public RelationshipManagerController(RelationshipManagerService relationshipManagerService) {
        this.relationshipManagerService = relationshipManagerService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RelationshipManagerResponse create(@Valid @RequestBody RelationshipManagerRequest request) {
        return relationshipManagerService.create(request);
    }

    @PatchMapping("/{id}")
    public RelationshipManagerResponse updateStatus(@PathVariable Long id,
                                                      @Valid @RequestBody RelationshipManagerStatusRequest request) {
        return relationshipManagerService.updateStatus(id, request.active());
    }
}

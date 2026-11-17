package com.techverito.banking.controller;

import com.techverito.banking.dto.TaskAcceptedResponse;
import com.techverito.banking.exception.GlobalExceptionHandler;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.service.AccountService;
import com.techverito.banking.service.TaskSubmissionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Statement submission: the request is queued as background work and answered with 202 and
 * the task id, an unknown account is a 404, and a missing period is a 400 - no statement
 * content is ever produced by the request itself.
 */
@WebMvcTest({StatementController.class, GlobalExceptionHandler.class})
class StatementControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    AccountService accountService;

    @MockBean
    TaskSubmissionService taskSubmissionService;

    @Test
    void POST_accounts_accountId_statements_returns202WithTaskId() throws Exception {
        when(taskSubmissionService.submitStatementGeneration(eq(10L), eq(LocalDate.parse("2025-02-01")),
                eq(LocalDate.parse("2025-02-28")), eq("ops.user"), anyString()))
                .thenReturn(new TaskAcceptedResponse(3L, "STATEMENT_GENERATION", "PENDING"));

        mockMvc.perform(post("/accounts/10/statements")
                        .param("periodStart", "2025-02-01")
                        .param("periodEnd", "2025-02-28")
                        .param("requestedBy", "ops.user"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value(3))
                .andExpect(jsonPath("$.taskType").value("STATEMENT_GENERATION"))
                .andExpect(jsonPath("$.status").value("PENDING"));

        verify(taskSubmissionService).submitStatementGeneration(eq(10L), eq(LocalDate.parse("2025-02-01")),
                eq(LocalDate.parse("2025-02-28")), eq("ops.user"), anyString());
    }

    @Test
    void POST_statements_unknownAccount_returns404ProblemDetailAndQueuesNothing() throws Exception {
        when(accountService.getById(99L)).thenThrow(new ResourceNotFoundException("Account", 99L));

        mockMvc.perform(post("/accounts/99/statements")
                        .param("periodStart", "2025-02-01")
                        .param("periodEnd", "2025-02-28")
                        .param("requestedBy", "ops.user"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Account not found with id: 99"));

        verify(taskSubmissionService, never()).submitStatementGeneration(any(), any(), any(), any(), any());
    }

    @Test
    void POST_statements_missingPeriod_returns400ProblemDetailAndQueuesNothing() throws Exception {
        mockMvc.perform(post("/accounts/10/statements")
                        .param("periodEnd", "2025-02-28")
                        .param("requestedBy", "ops.user"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value(containsString("periodStart")));

        verify(taskSubmissionService, never()).submitStatementGeneration(any(), any(), any(), any(), any());
    }
}

package com.techverito.banking.controller;

import com.techverito.banking.dto.TaskAcceptedResponse;
import com.techverito.banking.exception.ExportValidationException;
import com.techverito.banking.exception.GlobalExceptionHandler;
import com.techverito.banking.service.ExportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The export endpoint now submits background work instead of streaming CSV: every accepted
 * request answers 202 with the task id to poll, and nothing writes report content into the
 * response. A rejected request is still the usual 400 ProblemDetail and submits no task.
 */
@WebMvcTest({ExportController.class, GlobalExceptionHandler.class})
class ExportControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    ExportService exportService;

    /** Stubs the service so the endpoint accepts the export as task number {@code taskId}. */
    private void givenAccepted(String requestType, String exportedBy, Long taskId) {
        when(exportService.requestExport(eq(requestType), eq(exportedBy), anyString()))
                .thenReturn(new TaskAcceptedResponse(taskId, "DOCUMENT_EXPORT", "PENDING"));
    }

    @Test
    void GET_exports_customers_returns202WithTaskIdAndNoCsvBody() throws Exception {
        givenAccepted("customers", "ops.user", 7L);

        MvcResult result = mockMvc.perform(get("/exports")
                        .queryParam("type", "customers")
                        .queryParam("exportedBy", "ops.user"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value(7))
                .andExpect(jsonPath("$.taskType").value("DOCUMENT_EXPORT"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(header().string(HttpHeaders.LOCATION, "/tasks/7"))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("Customer Reference");
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
        assertThat(result.getResponse().getContentType()).isNotEqualTo("text/csv");
        verify(exportService).requestExport(eq("customers"), eq("ops.user"), anyString());
    }

    @Test
    void GET_exports_accounts_returns202WithItsOwnTaskId() throws Exception {
        givenAccepted("accounts", "ops.user", 8L);

        mockMvc.perform(get("/exports")
                        .queryParam("type", "accounts")
                        .queryParam("exportedBy", "ops.user"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.taskId").value(8))
                .andExpect(jsonPath("$.taskType").value("DOCUMENT_EXPORT"))
                .andExpect(header().string(HttpHeaders.LOCATION, "/tasks/8"));

        verify(exportService).requestExport(eq("accounts"), eq("ops.user"), anyString());
    }

    @Test
    void GET_exports_both_returns202AndNoReportContent() throws Exception {
        givenAccepted("both", "ops.user", 9L);

        MvcResult result = mockMvc.perform(get("/exports")
                        .queryParam("type", "both")
                        .queryParam("exportedBy", "ops.user"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).doesNotContain("Account Number", "Customer Reference");
        verify(exportService).requestExport(eq("both"), eq("ops.user"), anyString());
    }

    @Test
    void GET_exports_missingExportedBy_returns400ProblemDetailAndSubmitsNothing() throws Exception {
        MvcResult result = mockMvc.perform(get("/exports").queryParam("type", "customers"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value(containsString("exportedBy")))
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("exportedBy");
        verify(exportService, never()).requestExport(any(), any(), any());
    }

    @Test
    void GET_exports_blankExportedBy_returns400ProblemDetailAndSubmitsNothing() throws Exception {
        when(exportService.requestExport(eq("customers"), eq("   "), anyString()))
                .thenThrow(new ExportValidationException(
                        "The name of the user running the export (exportedBy) is required."));

        MvcResult result = mockMvc.perform(get("/exports")
                        .queryParam("type", "customers")
                        .queryParam("exportedBy", "   "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value(containsString("exportedBy")))
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("exportedBy");
        verify(exportService).requestExport(eq("customers"), eq("   "), anyString());
    }

    @Test
    void GET_exports_unknownType_returns400ProblemDetailAndSubmitsNothing() throws Exception {
        when(exportService.requestExport(eq("invoices"), eq("ops.user"), anyString()))
                .thenThrow(new ExportValidationException(
                        "Unknown export type: invoices. Use customers, accounts or both."));

        MvcResult result = mockMvc.perform(get("/exports")
                        .queryParam("type", "invoices")
                        .queryParam("exportedBy", "ops.user"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").exists())
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("invoices");
        verify(exportService, never()).write(any(), any());
    }
}

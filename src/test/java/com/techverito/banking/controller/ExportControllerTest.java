package com.techverito.banking.controller;

import com.techverito.banking.dto.PreparedExport;
import com.techverito.banking.entity.ExportType;
import com.techverito.banking.exception.ExportValidationException;
import com.techverito.banking.exception.GlobalExceptionHandler;
import com.techverito.banking.service.ExportService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({ExportController.class, GlobalExceptionHandler.class})
class ExportControllerTest {

    private static final String FILE_NAME = "banking-export-2025-03-04-0907.csv";

    private static final String CUSTOMERS_CSV =
            "Customer Reference,Full Name,Email,Status,Created Date\r\n"
                    + "1,John Doe,john@example.com,ACTIVE,2025-03-04 09:07\r\n";

    private static final String ACCOUNTS_CSV =
            "Account Number,Account Type,Status,Balance,Customer Reference\r\n"
                    + "\"=\"\"00012345\"\"\",SAVINGS,ACTIVE,1000.00,1\r\n";

    private static final String BOTH_CSV = CUSTOMERS_CSV + "\r\n" + ACCOUNTS_CSV;

    @Autowired
    MockMvc mockMvc;

    @MockBean
    ExportService exportService;

    /** Stubs the service so the endpoint streams the given CSV content for the given type. */
    private void givenCsv(String requestType, ExportType type, String csv) throws IOException {
        PreparedExport plan = new PreparedExport(type, "ops.user", FILE_NAME);
        when(exportService.prepare(requestType, "ops.user")).thenReturn(plan);
        doAnswer(invocation -> {
            OutputStream out = invocation.getArgument(1);
            out.write(csv.getBytes(StandardCharsets.UTF_8));
            return null;
        }).when(exportService).write(eq(plan), any(OutputStream.class));
    }

    /** Performs the request, waits for the async dispatch and returns the downloaded response. */
    private MvcResult download(String type) throws Exception {
        MvcResult started = mockMvc.perform(get("/exports")
                        .queryParam("type", type)
                        .queryParam("exportedBy", "ops.user"))
                .andExpect(request().asyncStarted())
                .andReturn();
        return mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();
    }

    @Test
    void GET_exports_customers_returns200WithCsvBody() throws Exception {
        givenCsv("customers", ExportType.CUSTOMERS, CUSTOMERS_CSV);

        MvcResult result = download("customers");

        assertThat(result.getResponse().getContentAsByteArray())
                .isEqualTo(CUSTOMERS_CSV.getBytes(StandardCharsets.UTF_8));
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .isEqualTo(CUSTOMERS_CSV);
        verify(exportService).write(any(PreparedExport.class), any(OutputStream.class));
    }

    @Test
    void GET_exports_customers_contentTypeIsCsvUtf8() throws Exception {
        givenCsv("customers", ExportType.CUSTOMERS, CUSTOMERS_CSV);

        MvcResult result = download("customers");

        String contentType = result.getResponse().getContentType();
        assertThat(contentType).isNotNull();
        assertThat(MediaType.parseMediaType(contentType).isCompatibleWith(MediaType.parseMediaType("text/csv")))
                .isTrue();
        assertThat(contentType.toLowerCase(Locale.ROOT)).contains("charset=utf-8");
    }

    @Test
    void GET_exports_contentDispositionIsAttachmentWithTheGeneratedFileName() throws Exception {
        givenCsv("customers", ExportType.CUSTOMERS, CUSTOMERS_CSV);

        MvcResult result = download("customers");

        String contentDisposition = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(contentDisposition).startsWith("attachment");
        assertThat(ContentDisposition.parse(contentDisposition).getFilename()).isEqualTo(FILE_NAME);
    }

    @Test
    void GET_exports_bodyIsProducedOnTheAsyncDispatch() throws Exception {
        givenCsv("customers", ExportType.CUSTOMERS, CUSTOMERS_CSV);

        MvcResult started = mockMvc.perform(get("/exports")
                        .queryParam("type", "customers")
                        .queryParam("exportedBy", "ops.user"))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult dispatched = mockMvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(dispatched.getResponse().getContentAsByteArray())
                .isEqualTo(CUSTOMERS_CSV.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void GET_exports_accounts_returns200WithAccountsCsv() throws Exception {
        givenCsv("accounts", ExportType.ACCOUNTS, ACCOUNTS_CSV);

        MvcResult result = download("accounts");

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(ACCOUNTS_CSV);
        assertThat(ContentDisposition.parse(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .getFilename()).isEqualTo(FILE_NAME);
        verify(exportService).write(any(PreparedExport.class), any(OutputStream.class));
    }

    @Test
    void GET_exports_both_returns200WithBothSections() throws Exception {
        givenCsv("both", ExportType.BOTH, BOTH_CSV);

        MvcResult result = download("both");

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(body).isEqualTo(BOTH_CSV);
        assertThat(body).contains("Customer Reference,Full Name,Email,Status,Created Date");
        assertThat(body).contains("Account Number,Account Type,Status,Balance,Customer Reference");
        assertThat(ContentDisposition.parse(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .getFilename()).isEqualTo(FILE_NAME);
    }

    @Test
    void GET_exports_missingExportedBy_returns400ProblemDetailAndWritesNothing() throws Exception {
        MvcResult result = mockMvc.perform(get("/exports").queryParam("type", "customers"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.title").exists())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.detail").value(containsString("exportedBy")))
                .andReturn();

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).contains("exportedBy");
        verify(exportService, never()).write(any(), any());
    }

    @Test
    void GET_exports_blankExportedBy_returns400ProblemDetailAndWritesNothing() throws Exception {
        when(exportService.prepare("customers", "   "))
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
        verify(exportService, never()).write(any(), any());
    }

    @Test
    void GET_exports_unknownType_returns400ProblemDetailAndWritesNothing() throws Exception {
        when(exportService.prepare("invoices", "ops.user"))
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

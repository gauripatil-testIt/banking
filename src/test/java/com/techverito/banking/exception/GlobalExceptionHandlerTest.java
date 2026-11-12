package com.techverito.banking.exception;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.techverito.banking.entity.DocumentType;
import com.techverito.banking.storage.DocumentStorageException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode assertProblemDetailShape(ProblemDetail problemDetail) throws Exception {
        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(problemDetail));
        assertThat(node.has("type")).as("serialized key 'type'").isTrue();
        assertThat(node.has("title")).as("serialized key 'title'").isTrue();
        assertThat(node.has("status")).as("serialized key 'status'").isTrue();
        assertThat(node.has("detail")).as("serialized key 'detail'").isTrue();
        return node;
    }

    @Test
    void documentValidation_returns400WithRuleAsDetail() throws Exception {
        String rule = "Only PDF, JPG and PNG files are allowed, up to 10 MB.";

        ProblemDetail pd = handler.handleDocumentValidation(new DocumentValidationException(rule));

        assertThat(pd.getStatus()).isEqualTo(400);
        assertThat(pd.getDetail()).isEqualTo(rule);
        JsonNode node = assertProblemDetailShape(pd);
        assertThat(node.get("detail").asText()).isEqualTo(rule);
    }

    @Test
    void maxUploadSize_knownLimit_returns400WithLimitInDetail() throws Exception {
        ProblemDetail pd = handler.handleMaxUploadSize(new MaxUploadSizeExceededException(10 * 1024 * 1024L));

        assertThat(pd.getStatus()).isEqualTo(400);
        assertThat(pd.getDetail()).contains("10 MB");
        assertProblemDetailShape(pd);
    }

    @Test
    void maxUploadSize_unknownLimit_returns400WithGenericDetail() throws Exception {
        ProblemDetail pd = handler.handleMaxUploadSize(new MaxUploadSizeExceededException(-1L));

        assertThat(pd.getStatus()).isEqualTo(400);
        assertThat(pd.getDetail()).isNotBlank();
        assertProblemDetailShape(pd);
    }

    @Test
    void missingServletRequestPart_returns400() throws Exception {
        ProblemDetail pd = handler.handleMissingMultipartInput(new MissingServletRequestPartException("uploadedBy"));

        assertThat(pd.getStatus()).isEqualTo(400);
        assertThat(pd.getDetail()).contains("uploadedBy");
        assertProblemDetailShape(pd);
    }

    @Test
    void missingServletRequestParameter_returns400() throws Exception {
        ProblemDetail pd = handler.handleMissingMultipartInput(
                new MissingServletRequestParameterException("uploadedBy", "String"));

        assertThat(pd.getStatus()).isEqualTo(400);
        assertThat(pd.getDetail()).isNotBlank();
        assertProblemDetailShape(pd);
    }

    @Test
    void methodArgumentTypeMismatch_returns400WithDetail() throws Exception {
        ProblemDetail pd = handler.handleMissingMultipartInput(
                new MethodArgumentTypeMismatchException("NOT_A_TYPE", DocumentType.class, "documentType", null, null));

        assertThat(pd.getStatus()).isEqualTo(400);
        assertThat(pd.getDetail()).isNotBlank();
        assertProblemDetailShape(pd);
    }

    @Test
    void storageFailure_returns500() throws Exception {
        ProblemDetail pd = handler.handleStorageFailure(
                new DocumentStorageException("Could not store file: invoice.pdf", new IOException("disk full")));

        assertThat(pd.getStatus()).isEqualTo(500);
        assertThat(pd.getDetail()).contains("Could not store file");
        assertProblemDetailShape(pd);
    }

    @Test
    void dataAccessFailure_returns500() throws Exception {
        ProblemDetail pd = handler.handleDataAccessFailure(new DataIntegrityViolationException("insert failed"));

        assertThat(pd.getStatus()).isEqualTo(500);
        assertThat(pd.getDetail()).isNotBlank();
        assertProblemDetailShape(pd);
    }
}

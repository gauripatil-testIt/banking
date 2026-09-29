package com.techverito.banking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techverito.banking.dto.PasswordResetConfirmRequest;
import com.techverito.banking.dto.PasswordResetRequest;
import com.techverito.banking.exception.GlobalExceptionHandler;
import com.techverito.banking.exception.InvalidOrExpiredTokenException;
import com.techverito.banking.service.PasswordResetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({PasswordResetController.class, GlobalExceptionHandler.class})
class PasswordResetControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    PasswordResetService passwordResetService;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void POST_passwordReset_request_knownEmail_returns200() throws Exception {
        doNothing().when(passwordResetService).requestReset("john@example.com");

        mockMvc.perform(post("/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PasswordResetRequest("john@example.com"))))
                .andExpect(status().isOk());
    }

    @Test
    void POST_passwordReset_request_unknownEmail_returns200() throws Exception {
        doNothing().when(passwordResetService).requestReset("unknown@example.com");

        mockMvc.perform(post("/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PasswordResetRequest("unknown@example.com"))))
                .andExpect(status().isOk());
    }

    @Test
    void POST_passwordReset_request_invalidEmail_returns400() throws Exception {
        mockMvc.perform(post("/password-reset/request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new PasswordResetRequest("not-an-email"))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(passwordResetService);
    }

    @Test
    void POST_passwordReset_confirm_validToken_returns200() throws Exception {
        doNothing().when(passwordResetService).confirmReset(anyString(), anyString());

        mockMvc.perform(post("/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PasswordResetConfirmRequest("valid-token", "Str0ng!Pass"))))
                .andExpect(status().isOk());
    }

    @Test
    void POST_passwordReset_confirm_invalidOrExpiredToken_returns400() throws Exception {
        doThrow(new InvalidOrExpiredTokenException("Invalid or expired token"))
                .when(passwordResetService).confirmReset(anyString(), anyString());

        mockMvc.perform(post("/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PasswordResetConfirmRequest("bad-token", "Str0ng!Pass"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_passwordReset_confirm_weakPassword_returns400() throws Exception {
        mockMvc.perform(post("/password-reset/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new PasswordResetConfirmRequest("some-token", "weak"))))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(passwordResetService);
    }
}

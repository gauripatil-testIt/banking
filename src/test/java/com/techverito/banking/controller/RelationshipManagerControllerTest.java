package com.techverito.banking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techverito.banking.dto.RelationshipManagerRequest;
import com.techverito.banking.dto.RelationshipManagerResponse;
import com.techverito.banking.exception.BadRequestException;
import com.techverito.banking.exception.GlobalExceptionHandler;
import com.techverito.banking.service.RelationshipManagerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({RelationshipManagerController.class, GlobalExceptionHandler.class})
class RelationshipManagerControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    RelationshipManagerService relationshipManagerService;

    @Autowired
    ObjectMapper objectMapper;

    private RelationshipManagerRequest validRequest() {
        return new RelationshipManagerRequest("Alice", "alice@example.com");
    }

    private RelationshipManagerResponse response(Long id) {
        return new RelationshipManagerResponse(id, "Alice", "alice@example.com", true);
    }

    @Test
    void POST_relationshipManagers_returns201() throws Exception {
        when(relationshipManagerService.create(any())).thenReturn(response(1L));

        mockMvc.perform(post("/relationship-managers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.name").value("Alice"))
                .andExpect(jsonPath("$.active").value(true));
    }

    @Test
    void POST_relationshipManagers_duplicateEmail_returns400() throws Exception {
        when(relationshipManagerService.create(any()))
                .thenThrow(new BadRequestException("A relationship manager with email alice@example.com already exists"));

        mockMvc.perform(post("/relationship-managers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(validRequest())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void POST_relationshipManagers_invalidBody_returns400() throws Exception {
        RelationshipManagerRequest invalid = new RelationshipManagerRequest("", "not-an-email");

        mockMvc.perform(post("/relationship-managers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalid)))
                .andExpect(status().isBadRequest());
    }
}

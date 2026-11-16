package com.techverito.banking.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.techverito.banking.dto.BatchReplayRequest;
import com.techverito.banking.dto.DeadLetterResponse;
import com.techverito.banking.dto.ReplayRequest;
import com.techverito.banking.entity.DeadLetterStatus;
import com.techverito.banking.event.EventType;
import com.techverito.banking.exception.GlobalExceptionHandler;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.service.DeadLetterReplayService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The operations API of the dead-letter store: listing, reading one item, and replaying one
 * or a batch of them. The service is mocked, so every assertion here is about what the API
 * answers and what it asks the service to do – including that a rejected request replays
 * nothing.
 */
@WebMvcTest({DeadLetterController.class, GlobalExceptionHandler.class})
class DeadLetterControllerTest {

    private static final Instant NOW = Instant.parse("2025-03-04T09:07:45Z");

    private static final String SOURCE = "business-event-outbox";

    private static final String EVENT_ID = "0e0b9c8d-1a2b-3c4d-8e9f-0a1b2c3d4e5f";

    private static final String PAYLOAD = "{\"eventId\":\"" + EVENT_ID + "\"}";

    @Autowired
    MockMvc mockMvc;

    @MockBean
    DeadLetterReplayService deadLetterReplayService;

    @Autowired
    ObjectMapper objectMapper;

    private DeadLetterResponse response(Long id, DeadLetterStatus status) {
        return new DeadLetterResponse(id, SOURCE, EVENT_ID, EventType.CUSTOMER_REGISTERED, PAYLOAD,
                "the broker did not acknowledge the record", 5, NOW, NOW, NOW, status, null, null, 0);
    }

    private DeadLetterResponse replayed(Long id) {
        return new DeadLetterResponse(id, SOURCE, EVENT_ID, EventType.CUSTOMER_REGISTERED, PAYLOAD,
                "the broker did not acknowledge the record", 5, NOW, NOW, NOW, DeadLetterStatus.REPLAYED,
                "ops.alice", NOW, 1);
    }

    @Test
    void GET_deadLetters_returnsEveryItemWithItsContents() throws Exception {
        when(deadLetterReplayService.list(isNull())).thenReturn(List.of(
                response(1L, DeadLetterStatus.PENDING), replayed(2L)));

        mockMvc.perform(get("/dead-letters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].source").value(SOURCE))
                .andExpect(jsonPath("$[0].sourceId").value(EVENT_ID))
                .andExpect(jsonPath("$[0].eventType").value("CUSTOMER_REGISTERED"))
                .andExpect(jsonPath("$[0].payload").value(PAYLOAD))
                .andExpect(jsonPath("$[0].reason").value("the broker did not acknowledge the record"))
                .andExpect(jsonPath("$[0].attempts").value(5))
                .andExpect(jsonPath("$[0].firstFailedAt").exists())
                .andExpect(jsonPath("$[0].lastFailedAt").exists())
                .andExpect(jsonPath("$[0].deadLetteredAt").exists())
                .andExpect(jsonPath("$[0].status").value("PENDING"))
                .andExpect(jsonPath("$[0].replayCount").value(0))
                .andExpect(jsonPath("$[1].status").value("REPLAYED"))
                .andExpect(jsonPath("$[1].replayedBy").value("ops.alice"));
    }

    @Test
    void GET_deadLetters_withStatus_returnsOnlyTheItemsInThatState() throws Exception {
        when(deadLetterReplayService.list(DeadLetterStatus.PENDING))
                .thenReturn(List.of(response(1L, DeadLetterStatus.PENDING)));

        mockMvc.perform(get("/dead-letters").param("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].status").value("PENDING"));

        verify(deadLetterReplayService).list(DeadLetterStatus.PENDING);
    }

    @Test
    void GET_deadLetters_id_found_returns200() throws Exception {
        when(deadLetterReplayService.get(1L)).thenReturn(response(1L, DeadLetterStatus.PENDING));

        mockMvc.perform(get("/dead-letters/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.sourceId").value(EVENT_ID));
    }

    @Test
    void GET_deadLetters_id_notFound_returns404ProblemDetail() throws Exception {
        when(deadLetterReplayService.get(99L)).thenThrow(new ResourceNotFoundException("Dead-lettered item", 99L));

        mockMvc.perform(get("/dead-letters/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Dead-lettered item not found with id: 99"));
    }

    @Test
    void POST_deadLetters_id_replay_returns200AndRecordsWhoReplayedIt() throws Exception {
        when(deadLetterReplayService.replay(1L, "ops.alice")).thenReturn(replayed(1L));

        mockMvc.perform(post("/dead-letters/1/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReplayRequest("ops.alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.status").value("REPLAYED"))
                .andExpect(jsonPath("$.replayedBy").value("ops.alice"))
                .andExpect(jsonPath("$.replayCount").value(1));

        verify(deadLetterReplayService).replay(1L, "ops.alice");
    }

    @Test
    void POST_deadLetters_id_replay_withoutReplayedBy_returns400AndReplaysNothing() throws Exception {
        mockMvc.perform(post("/dead-letters/1/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value("Validation failed"))
                .andExpect(jsonPath("$.errors.replayedBy").exists());

        verify(deadLetterReplayService, never()).replay(eq(1L), anyString());
    }

    @Test
    void POST_deadLetters_id_replay_withBlankReplayedBy_returns400AndReplaysNothing() throws Exception {
        mockMvc.perform(post("/dead-letters/1/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReplayRequest("   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.replayedBy").exists());

        verify(deadLetterReplayService, never()).replay(eq(1L), anyString());
    }

    @Test
    void POST_deadLetters_id_replay_unknownId_returns404ProblemDetail() throws Exception {
        when(deadLetterReplayService.replay(99L, "ops.alice"))
                .thenThrow(new ResourceNotFoundException("Dead-lettered item", 99L));

        mockMvc.perform(post("/dead-letters/99/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ReplayRequest("ops.alice"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Dead-lettered item not found with id: 99"));
    }

    @Test
    void POST_deadLetters_replay_returnsEveryReplayedItem() throws Exception {
        when(deadLetterReplayService.replayBatch(List.of(1L, 2L), "ops.alice"))
                .thenReturn(List.of(replayed(1L), replayed(2L)));

        mockMvc.perform(post("/dead-letters/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BatchReplayRequest(List.of(1L, 2L), "ops.alice"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].replayedBy").value("ops.alice"))
                .andExpect(jsonPath("$[1].replayedBy").value("ops.alice"));

        verify(deadLetterReplayService).replayBatch(List.of(1L, 2L), "ops.alice");
    }

    @Test
    void POST_deadLetters_replay_withAnUnknownId_returns404AndReplaysNothing() throws Exception {
        when(deadLetterReplayService.replayBatch(anyList(), anyString()))
                .thenThrow(new ResourceNotFoundException("Dead-lettered item", 99L));

        mockMvc.perform(post("/dead-letters/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BatchReplayRequest(List.of(1L, 99L), "ops.alice"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Dead-lettered item not found with id: 99"));
    }

    @Test
    void POST_deadLetters_replay_withoutIds_returns400AndReplaysNothing() throws Exception {
        mockMvc.perform(post("/dead-letters/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new BatchReplayRequest(List.of(), "ops.alice"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.ids").exists());

        verify(deadLetterReplayService, never()).replayBatch(anyList(), anyString());
    }

    @Test
    void POST_deadLetters_replay_withoutReplayedBy_returns400AndReplaysNothing() throws Exception {
        mockMvc.perform(post("/dead-letters/replay")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new BatchReplayRequest(List.of(1L), " "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.replayedBy").exists());

        verify(deadLetterReplayService, never()).replayBatch(anyList(), anyString());
    }
}

package com.techverito.banking.controller;

import com.techverito.banking.dto.TaskStatusResponse;
import com.techverito.banking.exception.GlobalExceptionHandler;
import com.techverito.banking.exception.ResourceNotFoundException;
import com.techverito.banking.service.TaskSubmissionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The task id a submission answers with is polled here: the task type, its status and the
 * time the broker confirmed it, or the usual 404 ProblemDetail for an unknown id.
 */
@WebMvcTest({TaskController.class, GlobalExceptionHandler.class})
class TaskControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    TaskSubmissionService taskSubmissionService;

    @Test
    void GET_tasks_taskId_returns200WithTheTaskView() throws Exception {
        when(taskSubmissionService.status(7L)).thenReturn(new TaskStatusResponse(7L, "DOCUMENT_SCAN",
                "SUBMITTED", Instant.parse("2025-03-04T09:07:45Z")));

        mockMvc.perform(get("/tasks/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taskId").value(7))
                .andExpect(jsonPath("$.taskType").value("DOCUMENT_SCAN"))
                .andExpect(jsonPath("$.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.publishedAt").value("2025-03-04T09:07:45Z"));
    }

    @Test
    void GET_tasks_pendingTask_returnsNoPublishedTime() throws Exception {
        when(taskSubmissionService.status(8L)).thenReturn(new TaskStatusResponse(8L, "DOCUMENT_EXPORT",
                "PENDING", null));

        mockMvc.perform(get("/tasks/8"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.publishedAt").value(nullValue()));
    }

    @Test
    void GET_tasks_unknownTask_returns404ProblemDetail() throws Exception {
        when(taskSubmissionService.status(99L)).thenThrow(new ResourceNotFoundException("Task", 99L));

        mockMvc.perform(get("/tasks/99"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").exists())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Task not found with id: 99"));
    }
}

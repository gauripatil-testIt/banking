package com.techverito.banking.controller;

import com.techverito.banking.dto.TaskStatusResponse;
import com.techverito.banking.service.TaskSubmissionService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The task id the submission endpoints answer with is polled here:
 * {@code GET /tasks/{taskId}} returns the task type, its outbox status and the time the
 * broker confirmed it (null while it is still PENDING). An unknown id is the application's
 * usual 404 ProblemDetail.
 */
@RestController
@RequestMapping("/tasks")
public class TaskController {

    private final TaskSubmissionService taskSubmissionService;

    public TaskController(TaskSubmissionService taskSubmissionService) {
        this.taskSubmissionService = taskSubmissionService;
    }

    @GetMapping("/{taskId}")
    public TaskStatusResponse get(@PathVariable Long taskId) {
        return taskSubmissionService.status(taskId);
    }
}

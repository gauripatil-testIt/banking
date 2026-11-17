package com.techverito.banking.controller;

import com.techverito.banking.dto.TaskAcceptedResponse;
import com.techverito.banking.service.ExportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

/**
 * The CSV export: {@code GET /exports?type=customers|accounts|both&exportedBy=<name>}.
 *
 * <p>The export is slow work, so nothing is streamed here any more: the request is
 * validated by {@link ExportService#requestExport(String, String, String)} and queued for a
 * background worker, and the caller gets {@code 202 Accepted} with the task id to poll on
 * {@code GET /tasks/{taskId}}. An unknown type or a missing uploader is still answered with
 * the application's usual 400 ProblemDetail, and leaves no task behind.</p>
 */
@RestController
@RequestMapping("/exports")
public class ExportController {

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping
    public ResponseEntity<TaskAcceptedResponse> export(@RequestParam("type") String type,
                                                       @RequestParam("exportedBy") String exportedBy) {
        TaskAcceptedResponse accepted = exportService.requestExport(type, exportedBy,
                UUID.randomUUID().toString());
        return ResponseEntity.accepted()
                .location(URI.create("/tasks/" + accepted.taskId()))
                .body(accepted);
    }
}

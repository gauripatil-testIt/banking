package com.techverito.banking.controller;

import com.techverito.banking.dto.PreparedExport;
import com.techverito.banking.service.ExportService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.nio.charset.StandardCharsets;

/**
 * The CSV export: {@code GET /exports?type=customers|accounts|both&exportedBy=<name>}.
 *
 * <p>The request is validated by {@link ExportService#prepare(String, String)} before the
 * streaming body is built, so an unknown type or a missing uploader is still answered with
 * the application's usual 400 ProblemDetail instead of a half written file.</p>
 */
@RestController
@RequestMapping("/exports")
public class ExportController {

    private static final MediaType CSV_UTF8 = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final ExportService exportService;

    public ExportController(ExportService exportService) {
        this.exportService = exportService;
    }

    @GetMapping
    public ResponseEntity<StreamingResponseBody> export(@RequestParam("type") String type,
                                                        @RequestParam("exportedBy") String exportedBy) {
        PreparedExport plan = exportService.prepare(type, exportedBy);
        StreamingResponseBody body = outputStream -> exportService.write(plan, outputStream);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(plan.fileName(), StandardCharsets.UTF_8)
                        .build()
                        .toString())
                .contentType(CSV_UTF8)
                .body(body);
    }
}

package com.techverito.banking.dto;

import com.techverito.banking.entity.ExportType;

/**
 * A validated export request: what to include, who ran it and the file name to
 * download. Produced only by {@code ExportService.prepare}, so an invalid request
 * never reaches the streaming stage.
 */
public record PreparedExport(
        ExportType type,
        String exportedBy,
        String fileName
) {}

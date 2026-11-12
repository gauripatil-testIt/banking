package com.techverito.banking.dto;

import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;

/**
 * A downloadable document: the stored file is carried as a streaming resource so a
 * maximum size document is never materialised as a byte array.
 */
public record DocumentDownloadResult(
        Resource resource,
        String originalFileName,
        MediaType mediaType,
        long sizeBytes
) {}

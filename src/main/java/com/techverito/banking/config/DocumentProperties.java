package com.techverito.banking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import java.nio.file.Path;
import java.util.Set;

@ConfigurationProperties(prefix = "banking.documents")
public record DocumentProperties(
        @DefaultValue("./uploads/documents") Path storageDir,
        @DefaultValue({"pdf", "jpg", "png"}) Set<String> allowedExtensions,
        @DefaultValue("10MB") DataSize maxFileSize,
        @DefaultValue("S3") StorageType storage
) {}

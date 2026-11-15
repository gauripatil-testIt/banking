package com.techverito.banking.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;

/**
 * S3 settings for document storage. Bucket and region are supplied by the
 * environment and have no defaults, so an unset value stays null/blank and the
 * application can refuse to start rather than guess. The endpoint is optional:
 * it is only set when pointing at LocalStack or another S3-compatible service.
 */
@ConfigurationProperties(prefix = "banking.documents.s3")
public record S3Properties(
        String bucket,
        String region,
        URI endpoint
) {}

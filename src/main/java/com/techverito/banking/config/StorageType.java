package com.techverito.banking.config;

/**
 * Which document storage backend is active, selected with
 * {@code banking.documents.storage}. It defaults to {@link #S3} so a deployment
 * that forgets its S3 settings fails at startup instead of silently writing
 * documents to the application server's disk.
 */
public enum StorageType {
    LOCAL, S3
}

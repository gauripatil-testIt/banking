package com.techverito.banking.storage;

import com.techverito.banking.exception.DocumentContentUnavailableException;
import org.springframework.core.io.Resource;

/**
 * Storage seam for customer documents. BANK-5 re-implements this against S3
 * without changing the service behaviour.
 */
public interface DocumentStorage {

    /**
     * Stores the content under a system-assigned name and returns that name.
     */
    String store(byte[] content, String originalFileName);

    /**
     * Returns the stored file as a streaming resource, so a maximum size document
     * is never held in memory as a whole.
     *
     * @throws DocumentContentUnavailableException when the stored file is missing,
     *         is not a regular file, or cannot be opened
     */
    Resource load(String storedFileName);

    /**
     * Removes the stored file. Deleting a name that no longer exists does not throw.
     */
    void delete(String storedFileName);
}

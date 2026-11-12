package com.techverito.banking.storage;

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
     * Removes the stored file. Deleting a name that no longer exists does not throw.
     */
    void delete(String storedFileName);
}

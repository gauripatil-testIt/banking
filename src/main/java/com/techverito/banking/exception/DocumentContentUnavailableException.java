package com.techverito.banking.exception;

/**
 * Raised when a stored document file is missing or cannot be read. Downloads map
 * this to a 404, so the user is asked to upload the document again, while the
 * stored file name is kept for support logging.
 */
public class DocumentContentUnavailableException extends RuntimeException {

    private static final String MESSAGE =
            "The file for this document is no longer available. Please upload the document again.";

    private final String storedFileName;

    public DocumentContentUnavailableException(String storedFileName) {
        super(MESSAGE);
        this.storedFileName = storedFileName;
    }

    public DocumentContentUnavailableException(String storedFileName, Throwable cause) {
        super(MESSAGE, cause);
        this.storedFileName = storedFileName;
    }

    public String getStoredFileName() {
        return storedFileName;
    }
}

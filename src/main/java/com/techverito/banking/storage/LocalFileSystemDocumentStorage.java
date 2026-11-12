package com.techverito.banking.storage;

import com.techverito.banking.config.DocumentProperties;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Component
public class LocalFileSystemDocumentStorage implements DocumentStorage {

    private final Path storageDir;

    public LocalFileSystemDocumentStorage(DocumentProperties properties) {
        this.storageDir = properties.storageDir();
    }

    @PostConstruct
    void init() {
        createStorageDir();
    }

    @Override
    public String store(byte[] content, String originalFileName) {
        String storedFileName = generateStoredFileName(originalFileName);
        try {
            createStorageDir();
            Files.write(resolve(storedFileName), content);
            return storedFileName;
        } catch (IOException ex) {
            throw new DocumentStorageException("Could not store file: " + originalFileName, ex);
        }
    }

    @Override
    public void delete(String storedFileName) {
        try {
            Files.deleteIfExists(resolve(storedFileName));
        } catch (IOException ex) {
            throw new DocumentStorageException("Could not delete stored file: " + storedFileName, ex);
        }
    }

    private void createStorageDir() {
        try {
            Files.createDirectories(storageDir);
        } catch (IOException ex) {
            throw new DocumentStorageException("Could not create storage directory: " + storageDir, ex);
        }
    }

    private Path resolve(String storedFileName) {
        return storageDir.resolve(storedFileName);
    }

    private String generateStoredFileName(String originalFileName) {
        String extension = extensionOf(originalFileName);
        return extension.isEmpty()
                ? UUID.randomUUID().toString()
                : UUID.randomUUID() + "." + extension;
    }

    private String extensionOf(String originalFileName) {
        if (originalFileName == null) {
            return "";
        }
        int dot = originalFileName.lastIndexOf('.');
        if (dot < 0 || dot == originalFileName.length() - 1) {
            return "";
        }
        return originalFileName.substring(dot + 1).toLowerCase();
    }
}

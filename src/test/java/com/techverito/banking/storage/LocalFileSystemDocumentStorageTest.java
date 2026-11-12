package com.techverito.banking.storage;

import com.techverito.banking.config.DocumentProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class LocalFileSystemDocumentStorageTest {

    @TempDir
    Path storageDir;

    private LocalFileSystemDocumentStorage storage(Path dir) {
        return new LocalFileSystemDocumentStorage(
                new DocumentProperties(dir, Set.of("pdf", "jpg", "png"), DataSize.ofMegabytes(10)));
    }

    private byte[] content(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void store_writesContentUnderGeneratedName() throws Exception {
        LocalFileSystemDocumentStorage storage = storage(storageDir);

        String storedFileName = storage.store(content("pdf-bytes"), "invoice.PDF");

        assertThat(storedFileName).endsWith(".pdf");
        assertThat(storedFileName).doesNotContain("invoice");
        Path written = storageDir.resolve(storedFileName);
        assertThat(Files.exists(written)).isTrue();
        assertThat(Files.readAllBytes(written)).isEqualTo(content("pdf-bytes"));
    }

    @Test
    void store_sameOriginalFileNameTwice_keepsBothFiles() {
        LocalFileSystemDocumentStorage storage = storage(storageDir);

        String first = storage.store(content("first"), "statement.pdf");
        String second = storage.store(content("second"), "statement.pdf");

        assertThat(first).isNotEqualTo(second);
        assertThat(storageDir.resolve(first)).exists();
        assertThat(storageDir.resolve(second)).exists();
    }

    @Test
    void store_nameWithSpacesBracketsAndNonEnglishCharacters_succeeds() {
        LocalFileSystemDocumentStorage storage = storage(storageDir);

        String storedFileName = storage.store(content("data"), "pán kyc (final) [übersicht].jpg");

        assertThat(storedFileName).endsWith(".jpg");
        assertThat(storedFileName).matches("[0-9a-f\\-]{36}\\.jpg");
        assertThat(storageDir.resolve(storedFileName)).exists();
    }

    @Test
    void store_storageDirMissing_createsDirectory() {
        Path missing = storageDir.resolve("nested/documents");
        LocalFileSystemDocumentStorage storage = storage(missing);

        String storedFileName = storage.store(content("data"), "form.png");

        assertThat(missing).isDirectory();
        assertThat(missing.resolve(storedFileName)).exists();
    }

    @Test
    void delete_existingFile_removesIt() {
        LocalFileSystemDocumentStorage storage = storage(storageDir);
        String storedFileName = storage.store(content("data"), "proof.pdf");

        storage.delete(storedFileName);

        assertThat(storageDir.resolve(storedFileName)).doesNotExist();
    }

    @Test
    void delete_missingFile_doesNotThrow() {
        LocalFileSystemDocumentStorage storage = storage(storageDir);

        assertThatCode(() -> storage.delete("does-not-exist.pdf")).doesNotThrowAnyException();
    }
}

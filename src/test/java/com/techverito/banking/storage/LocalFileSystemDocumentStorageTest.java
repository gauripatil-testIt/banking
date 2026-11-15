package com.techverito.banking.storage;

import com.techverito.banking.config.DocumentProperties;
import com.techverito.banking.config.StorageType;
import com.techverito.banking.exception.DocumentContentUnavailableException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LocalFileSystemDocumentStorageTest {

    @TempDir
    Path storageDir;

    private LocalFileSystemDocumentStorage storage(Path dir) {
        return new LocalFileSystemDocumentStorage(
                new DocumentProperties(dir, Set.of("pdf", "jpg", "png"), DataSize.ofMegabytes(10),
                        StorageType.LOCAL));
    }

    private byte[] content(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void store_writesContentUnderGeneratedName() throws Exception {
        LocalFileSystemDocumentStorage storage = storage(storageDir);

        String storedFileName = storage.store(content("pdf-bytes"), "invoice.PDF", 1L);

        assertThat(storedFileName).endsWith(".pdf");
        assertThat(storedFileName).doesNotContain("invoice");
        Path written = storageDir.resolve(storedFileName);
        assertThat(Files.exists(written)).isTrue();
        assertThat(Files.readAllBytes(written)).isEqualTo(content("pdf-bytes"));
    }

    @Test
    void store_sameOriginalFileNameTwice_keepsBothFiles() {
        LocalFileSystemDocumentStorage storage = storage(storageDir);

        String first = storage.store(content("first"), "statement.pdf", 1L);
        String second = storage.store(content("second"), "statement.pdf", 1L);

        assertThat(first).isNotEqualTo(second);
        assertThat(storageDir.resolve(first)).exists();
        assertThat(storageDir.resolve(second)).exists();
    }

    @Test
    void store_nameWithSpacesBracketsAndNonEnglishCharacters_succeeds() {
        LocalFileSystemDocumentStorage storage = storage(storageDir);

        String storedFileName = storage.store(content("data"), "pán kyc (final) [übersicht].jpg", 1L);

        assertThat(storedFileName).endsWith(".jpg");
        assertThat(storedFileName).matches("[0-9a-f\\-]{36}\\.jpg");
        assertThat(storageDir.resolve(storedFileName)).exists();
    }

    @Test
    void store_storageDirMissing_createsDirectory() {
        Path missing = storageDir.resolve("nested/documents");
        LocalFileSystemDocumentStorage storage = storage(missing);

        String storedFileName = storage.store(content("data"), "form.png", 1L);

        assertThat(missing).isDirectory();
        assertThat(missing.resolve(storedFileName)).exists();
    }

    @Test
    void delete_existingFile_removesIt() {
        LocalFileSystemDocumentStorage storage = storage(storageDir);
        String storedFileName = storage.store(content("data"), "proof.pdf", 1L);

        storage.delete(storedFileName);

        assertThat(storageDir.resolve(storedFileName)).doesNotExist();
    }

    @Test
    void delete_missingFile_doesNotThrow() {
        LocalFileSystemDocumentStorage storage = storage(storageDir);

        assertThatCode(() -> storage.delete("does-not-exist.pdf")).doesNotThrowAnyException();
    }

    @Test
    void load_afterStore_returnsContentIdenticalToUploaded() throws Exception {
        LocalFileSystemDocumentStorage storage = storage(storageDir);
        byte[] uploaded = new byte[(int) DataSize.ofMegabytes(10).toBytes()];
        for (int i = 0; i < uploaded.length; i++) {
            uploaded[i] = (byte) (i % 251);
        }

        String storedFileName = storage.store(uploaded, "big.pdf", 1L);

        Resource resource = storage.load(storedFileName);
        byte[] loaded = resource.getInputStream().readAllBytes();

        assertThat(loaded).isEqualTo(uploaded);
        assertThat(loaded).hasSize(10 * 1024 * 1024);
    }

    @Test
    void load_nameWithSpacesBracketsAndNonEnglishCharacters_returnsIdenticalContent() throws Exception {
        LocalFileSystemDocumentStorage storage = storage(storageDir);
        byte[] uploaded = content("data with spaces, brackets [ü] and non-English characters");

        String storedFileName = storage.store(uploaded, "pán kyc (final) [übersicht].png", 1L);

        Resource resource = storage.load(storedFileName);

        assertThat(resource.getInputStream().readAllBytes()).isEqualTo(uploaded);
    }

    @Test
    void load_missingFile_throwsDocumentContentUnavailableException() {
        LocalFileSystemDocumentStorage storage = storage(storageDir);

        assertThatThrownBy(() -> storage.load("does-not-exist.pdf"))
                .isInstanceOfSatisfying(DocumentContentUnavailableException.class, ex -> {
                    assertThat(ex.getMessage()).isEqualTo(
                            "The file for this document is no longer available. Please upload the document again.");
                    assertThat(ex.getStoredFileName()).isEqualTo("does-not-exist.pdf");
                });
    }

    @Test
    void load_afterDelete_throwsDocumentContentUnavailableException() {
        LocalFileSystemDocumentStorage storage = storage(storageDir);
        String storedFileName = storage.store(content("data"), "proof.pdf", 1L);

        storage.delete(storedFileName);

        assertThatThrownBy(() -> storage.load(storedFileName))
                .isInstanceOf(DocumentContentUnavailableException.class);
    }
}

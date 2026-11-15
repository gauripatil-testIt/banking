package com.techverito.banking.repository;

import com.techverito.banking.entity.DataExport;
import com.techverito.banking.entity.ExportType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
class DataExportRepositoryTest {

    @Autowired
    DataExportRepository dataExportRepository;

    @Autowired
    TestEntityManager entityManager;

    @Test
    void save_thenReadBack_roundTripsWhoWhatRowCountAndTime() {
        Instant exportedAt = Instant.parse("2025-03-04T09:07:45Z");
        DataExport saved = dataExportRepository.save(DataExport.builder()
                .exportedBy("ops.user")
                .exportType(ExportType.BOTH)
                .recordCount(25L)
                .exportedAt(exportedAt)
                .build());
        dataExportRepository.flush();

        Long id = saved.getId();
        entityManager.clear();

        Optional<DataExport> reloaded = dataExportRepository.findById(id);

        assertThat(reloaded).isPresent();
        DataExport found = reloaded.orElseThrow();
        assertThat(found.getExportedBy()).isEqualTo("ops.user");
        assertThat(found.getExportType()).isEqualTo(ExportType.BOTH);
        assertThat(found.getRecordCount()).isEqualTo(25L);
        assertThat(found.getExportedAt()).isEqualTo(exportedAt);
    }
}

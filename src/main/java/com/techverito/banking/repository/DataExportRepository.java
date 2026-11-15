package com.techverito.banking.repository;

import com.techverito.banking.entity.DataExport;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataExportRepository extends JpaRepository<DataExport, Long> {
}

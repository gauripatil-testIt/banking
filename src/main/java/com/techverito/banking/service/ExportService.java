package com.techverito.banking.service;

import com.techverito.banking.dto.PreparedExport;
import com.techverito.banking.entity.Account;
import com.techverito.banking.entity.Customer;
import com.techverito.banking.entity.DataExport;
import com.techverito.banking.entity.ExportType;
import com.techverito.banking.exception.ExportValidationException;
import com.techverito.banking.repository.AccountRepository;
import com.techverito.banking.repository.CustomerRepository;
import com.techverito.banking.repository.DataExportRepository;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Builds the customer/account CSV export and records one audit row per export.
 *
 * <p>The request is validated in {@link #prepare(String, String)}, before anything
 * is streamed, so a rejected export produces neither a partial file nor an audit
 * row. {@link #write(PreparedExport, OutputStream)} reads the data per call and
 * holds no transaction while bytes go to the client, so a large export does not
 * occupy a database connection or an application lock.</p>
 */
@Service
public class ExportService {

    private static final DateTimeFormatter FILE_TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm").withZone(ZoneOffset.UTC);

    private final CustomerRepository customerRepository;
    private final AccountRepository accountRepository;
    private final DataExportRepository dataExportRepository;
    private final Clock clock;

    public ExportService(CustomerRepository customerRepository,
                         AccountRepository accountRepository,
                         DataExportRepository dataExportRepository,
                         Clock clock) {
        this.customerRepository = customerRepository;
        this.accountRepository = accountRepository;
        this.dataExportRepository = dataExportRepository;
        this.clock = clock;
    }

    /**
     * Validates the request and names the file. Does no I/O and writes no audit row.
     */
    public PreparedExport prepare(String rawType, String exportedBy) {
        ExportType type = parseType(rawType);
        if (exportedBy == null || exportedBy.isBlank()) {
            throw new ExportValidationException(
                    "The name of the user running the export (exportedBy) is required.");
        }
        String fileName = "banking-export-" + FILE_TIMESTAMP.format(Instant.now(clock)) + ".csv";
        return new PreparedExport(type, exportedBy, fileName);
    }

    /**
     * Writes the CSV for a prepared export, then saves the audit row. The supplied
     * stream is flushed but never closed.
     */
    public void write(PreparedExport plan, OutputStream out) throws IOException {
        Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8);
        long dataRows = 0L;
        if (plan.type().includesCustomers()) {
            dataRows += writeCustomers(writer);
        }
        if (plan.type() == ExportType.BOTH) {
            writer.write(CsvWriter.LINE_SEPARATOR);
        }
        if (plan.type().includesAccounts()) {
            dataRows += writeAccounts(writer);
        }
        writer.flush();

        dataExportRepository.save(DataExport.builder()
                .exportedBy(plan.exportedBy())
                .exportType(plan.type())
                .recordCount(dataRows)
                .exportedAt(Instant.now(clock))
                .build());
    }

    private long writeCustomers(Writer writer) throws IOException {
        CsvWriter.writeLine(writer, ExportHeaders.CUSTOMERS);
        List<Customer> customers = customerRepository.findAll().stream()
                .sorted(Comparator.comparing(Customer::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
        if (customers.isEmpty()) {
            CsvWriter.writeLine(writer, List.of(ExportHeaders.NO_RECORDS));
            return 0L;
        }
        for (Customer customer : customers) {
            CsvWriter.writeLine(writer, List.of(
                    reference(customer.getId()),
                    fullName(customer),
                    nullToEmpty(customer.getEmail()),
                    (customer.getStatus() != null) ? customer.getStatus().name() : "",
                    ExportFormatting.dateTime(customer.getCreatedAt())));
        }
        return customers.size();
    }

    private long writeAccounts(Writer writer) throws IOException {
        CsvWriter.writeLine(writer, ExportHeaders.ACCOUNTS);
        List<Account> accounts = accountRepository.findAllWithCustomer().stream()
                .sorted(Comparator.comparing(Account::getId, Comparator.nullsLast(Long::compareTo)))
                .toList();
        if (accounts.isEmpty()) {
            CsvWriter.writeLine(writer, List.of(ExportHeaders.NO_RECORDS));
            return 0L;
        }
        for (Account account : accounts) {
            Customer owner = account.getCustomer();
            CsvWriter.writeLine(writer, List.of(
                    ExportFormatting.accountNumberText(account.getAccountNumber()),
                    (account.getType() != null) ? account.getType().name() : "",
                    (account.getStatus() != null) ? account.getStatus().name() : "",
                    ExportFormatting.money(account.getBalance()),
                    (owner != null) ? reference(owner.getId()) : ""));
        }
        return accounts.size();
    }

    private String reference(Long id) {
        return (id != null) ? String.valueOf(id) : "";
    }

    private String fullName(Customer customer) {
        String first = nullToEmpty(customer.getFirstName());
        String last = nullToEmpty(customer.getLastName());
        if (first.isEmpty()) {
            return last;
        }
        if (last.isEmpty()) {
            return first;
        }
        return first + " " + last;
    }

    private String nullToEmpty(String value) {
        return (value != null) ? value : "";
    }

    private ExportType parseType(String rawType) {
        if (rawType == null || rawType.isBlank()) {
            throw new ExportValidationException(
                    "An export type is required. Use customers, accounts or both.");
        }
        String normalized = rawType.trim().toLowerCase(Locale.ROOT);
        for (ExportType candidate : ExportType.values()) {
            if (candidate.getWireValue().equals(normalized)) {
                return candidate;
            }
        }
        throw new ExportValidationException(
                "Unknown export type: " + rawType.trim() + ". Use customers, accounts or both.");
    }
}

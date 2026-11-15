package com.techverito.banking.service;

import com.techverito.banking.dto.PreparedExport;
import com.techverito.banking.entity.Account;
import com.techverito.banking.entity.AccountStatus;
import com.techverito.banking.entity.AccountType;
import com.techverito.banking.entity.Customer;
import com.techverito.banking.entity.CustomerStatus;
import com.techverito.banking.entity.DataExport;
import com.techverito.banking.entity.ExportType;
import com.techverito.banking.exception.ExportValidationException;
import com.techverito.banking.repository.AccountRepository;
import com.techverito.banking.repository.CustomerRepository;
import com.techverito.banking.repository.DataExportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExportServiceTest {

    private static final Instant NOW = Instant.parse("2025-03-04T09:07:45Z");

    @Mock
    CustomerRepository customerRepository;

    @Mock
    AccountRepository accountRepository;

    @Mock
    DataExportRepository dataExportRepository;

    private ExportService exportService;

    @BeforeEach
    void setUp() {
        exportService = new ExportService(customerRepository, accountRepository, dataExportRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Customer customer(Long id, String firstName, String lastName, String email,
                              CustomerStatus status, Instant createdAt) {
        return Customer.builder()
                .id(id).firstName(firstName).lastName(lastName)
                .email(email).phone("123")
                .status(status).createdAt(createdAt)
                .build();
    }

    private Account account(Long id, String accountNumber, AccountType type, AccountStatus status,
                            BigDecimal balance, Customer customer) {
        return Account.builder()
                .id(id).customer(customer)
                .accountNumber(accountNumber).type(type)
                .balance(balance).status(status)
                .build();
    }

    private String write(PreparedExport plan) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        exportService.write(plan, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private List<String> lines(String csv) {
        List<String> lines = new ArrayList<>(Arrays.asList(csv.split(CsvWriter.LINE_SEPARATOR, -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) {
            lines.remove(lines.size() - 1);
        }
        return lines;
    }

    /** Reads the whole file back into records, honouring the RFC 4180 quoting. */
    private List<List<String>> parseCsv(String csv) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean inQuotes = false;
        int i = 0;
        while (i < csv.length()) {
            char c = csv.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                        field.append('"');
                        i += 2;
                    } else {
                        inQuotes = false;
                        i++;
                    }
                } else {
                    field.append(c);
                    i++;
                }
            } else if (c == '"') {
                inQuotes = true;
                i++;
            } else if (c == ',') {
                record.add(field.toString());
                field.setLength(0);
                i++;
            } else if (c == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                record.add(field.toString());
                field.setLength(0);
                records.add(record);
                record = new ArrayList<>();
                i += 2;
            } else {
                field.append(c);
                i++;
            }
        }
        if (field.length() > 0 || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }

    private DataExport savedAuditRow() {
        ArgumentCaptor<DataExport> captor = ArgumentCaptor.forClass(DataExport.class);
        verify(dataExportRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void write_customers_writesHeaderThenOneRowPerCustomerOrderedById() throws Exception {
        Customer second = customer(2L, "Jane", "Smith", "jane@example.com", CustomerStatus.ACTIVE,
                Instant.parse("2025-01-02T03:04:05Z"));
        Customer first = customer(1L, "John", "Doe", "john@example.com", CustomerStatus.INACTIVE,
                Instant.parse("2024-12-31T23:00:00Z"));
        when(customerRepository.findAll()).thenReturn(List.of(second, first));

        String csv = write(exportService.prepare("customers", "ops.user"));

        assertThat(lines(csv)).containsExactly(
                "Customer Reference,Full Name,Email,Status,Created Date",
                "1,John Doe,john@example.com,INACTIVE,2024-12-31 23:00",
                "2,Jane Smith,jane@example.com,ACTIVE,2025-01-02 03:04");
        assertThat(savedAuditRow().getRecordCount()).isEqualTo(2L);
    }

    @Test
    void write_accounts_writesHeaderThenOneRowPerAccount() throws Exception {
        Customer owner = customer(5L, "John", "Doe", "john@example.com", CustomerStatus.ACTIVE, NOW);
        when(accountRepository.findAllWithCustomer()).thenReturn(List.of(
                account(1L, "ACC001", AccountType.SAVINGS, AccountStatus.ACTIVE, new BigDecimal("1000"), owner),
                account(2L, "ACC002", AccountType.CURRENT, AccountStatus.INACTIVE, new BigDecimal("1234.5"), owner)));

        String csv = write(exportService.prepare("accounts", "ops.user"));

        List<List<String>> records = parseCsv(csv);
        assertThat(records).hasSize(3);
        assertThat(records.get(0)).containsExactly(
                "Account Number", "Account Type", "Status", "Balance", "Customer Reference");
        assertThat(records.get(1)).containsExactly("=\"ACC001\"", "SAVINGS", "ACTIVE", "1000.00", "5");
        assertThat(records.get(2)).containsExactly("=\"ACC002\"", "CURRENT", "INACTIVE", "1234.50", "5");
        assertThat(savedAuditRow().getRecordCount()).isEqualTo(2L);
    }

    @Test
    void write_accountNumberWithLeadingZeros_isExportedTextProtectedAndKeepsZeros() throws Exception {
        Customer owner = customer(5L, "John", "Doe", "john@example.com", CustomerStatus.ACTIVE, NOW);
        when(accountRepository.findAllWithCustomer()).thenReturn(List.of(
                account(1L, "00012345", AccountType.SAVINGS, AccountStatus.ACTIVE, new BigDecimal("10"), owner)));

        String csv = write(exportService.prepare("accounts", "ops.user"));

        String accountNumberCell = parseCsv(csv).get(1).get(0);
        assertThat(accountNumberCell).isEqualTo("=\"00012345\"");
        assertThat(accountNumberCell.replace("=", "").replace("\"", "")).isEqualTo("00012345");
    }

    @Test
    void write_customerNameWithCommaQuoteAndLineBreak_isQuotedAndParsesBackExactly() throws Exception {
        String fullName = "Smith, \"Jane\" Doe\nJr";
        Customer tricky = customer(3L, "Smith, \"Jane\"", "Doe\nJr", "tricky@example.com",
                CustomerStatus.ACTIVE, NOW);
        when(customerRepository.findAll()).thenReturn(List.of(tricky));

        String csv = write(exportService.prepare("customers", "ops.user"));

        assertThat(csv).contains("\"Smith, \"\"Jane\"\" Doe\nJr\"");
        List<List<String>> records = parseCsv(csv);
        assertThat(records).hasSize(2);
        assertThat(records.get(1)).hasSize(5);
        assertThat(records.get(1).get(1)).isEqualTo(fullName);
    }

    @Test
    void write_nonEnglishName_isPreservedWhenUtf8BytesAreDecoded() throws Exception {
        Customer nonEnglish = customer(4L, "pán", "kyi (übersicht)", "pan@example.com",
                CustomerStatus.ACTIVE, NOW);
        when(customerRepository.findAll()).thenReturn(List.of(nonEnglish));

        String csv = write(exportService.prepare("customers", "ops.user"));

        assertThat(csv).contains("pán kyi (übersicht)");
        assertThat(parseCsv(csv).get(1).get(1)).isEqualTo("pán kyi (übersicht)");
    }

    @Test
    void write_customersWithNoRecords_writesHeaderAndNoteAndAuditsZeroRows() throws Exception {
        when(customerRepository.findAll()).thenReturn(List.of());

        String csv = write(exportService.prepare("customers", "ops.user"));

        assertThat(lines(csv)).containsExactly(
                "Customer Reference,Full Name,Email,Status,Created Date",
                "No records found");

        DataExport audit = savedAuditRow();
        assertThat(audit.getExportedBy()).isEqualTo("ops.user");
        assertThat(audit.getExportType()).isEqualTo(ExportType.CUSTOMERS);
        assertThat(audit.getRecordCount()).isZero();
    }

    @Test
    void write_accountsWithNoRecords_writesHeaderAndNoteAndAuditsZeroRows() throws Exception {
        when(accountRepository.findAllWithCustomer()).thenReturn(List.of());

        String csv = write(exportService.prepare("accounts", "ops.user"));

        assertThat(lines(csv)).containsExactly(
                "Account Number,Account Type,Status,Balance,Customer Reference",
                "No records found");

        DataExport audit = savedAuditRow();
        assertThat(audit.getExportType()).isEqualTo(ExportType.ACCOUNTS);
        assertThat(audit.getRecordCount()).isZero();
    }

    @Test
    void write_both_writesCustomersSectionBlankLineThenAccountsSectionAndOneAuditRow() throws Exception {
        Customer owner = customer(5L, "John", "Doe", "john@example.com", CustomerStatus.ACTIVE,
                Instant.parse("2025-01-02T03:04:05Z"));
        when(customerRepository.findAll()).thenReturn(List.of(owner));
        when(accountRepository.findAllWithCustomer()).thenReturn(List.of(
                account(1L, "ACC001", AccountType.SAVINGS, AccountStatus.ACTIVE, new BigDecimal("1000"), owner),
                account(2L, "ACC002", AccountType.CURRENT, AccountStatus.INACTIVE, new BigDecimal("2.5"), owner)));

        String csv = write(exportService.prepare("both", "ops.user"));

        List<List<String>> records = parseCsv(csv);
        assertThat(records).hasSize(6);
        assertThat(records.get(0)).containsExactly(
                "Customer Reference", "Full Name", "Email", "Status", "Created Date");
        assertThat(records.get(1)).containsExactly(
                "5", "John Doe", "john@example.com", "ACTIVE", "2025-01-02 03:04");
        assertThat(records.get(2)).containsExactly("");
        assertThat(records.get(3)).containsExactly(
                "Account Number", "Account Type", "Status", "Balance", "Customer Reference");
        assertThat(records.get(4)).containsExactly("=\"ACC001\"", "SAVINGS", "ACTIVE", "1000.00", "5");
        assertThat(records.get(5)).containsExactly("=\"ACC002\"", "CURRENT", "INACTIVE", "2.50", "5");

        DataExport audit = savedAuditRow();
        assertThat(audit.getExportType()).isEqualTo(ExportType.BOTH);
        assertThat(audit.getRecordCount()).isEqualTo(3L);
    }

    @Test
    void prepare_acceptsCustomersAccountsAndBothInAnyCase() {
        assertThat(exportService.prepare("customers", "ops.user").type()).isEqualTo(ExportType.CUSTOMERS);
        assertThat(exportService.prepare("Accounts", "ops.user").type()).isEqualTo(ExportType.ACCOUNTS);
        assertThat(exportService.prepare("BOTH", "ops.user").type()).isEqualTo(ExportType.BOTH);
    }

    @Test
    void prepare_unknownType_throwsValidationException() {
        assertThatThrownBy(() -> exportService.prepare("invoices", "ops.user"))
                .isInstanceOf(ExportValidationException.class)
                .hasMessageContaining("invoices");
    }

    @Test
    void prepare_missingOrBlankExportedBy_throwsValidationExceptionNamingExportedBy() {
        assertThatThrownBy(() -> exportService.prepare("customers", null))
                .isInstanceOf(ExportValidationException.class)
                .hasMessageContaining("exportedBy");

        assertThatThrownBy(() -> exportService.prepare("customers", "   "))
                .isInstanceOf(ExportValidationException.class)
                .hasMessageContaining("exportedBy");
    }

    @Test
    void prepare_buildsFileNameAndAuditTimeFromTheClockInUtc() throws Exception {
        when(customerRepository.findAll()).thenReturn(List.of());

        PreparedExport plan = exportService.prepare("customers", "ops.user");

        assertThat(plan.fileName()).isEqualTo("banking-export-2025-03-04-0907.csv");

        write(plan);

        assertThat(savedAuditRow().getExportedAt()).isEqualTo(NOW);
    }

    @Test
    void write_tenThousandAccounts_writesHeaderPlusTenThousandRowsInUnderTenSeconds() throws Exception {
        Customer owner = customer(1L, "John", "Doe", "john@example.com", CustomerStatus.ACTIVE, NOW);
        List<Account> accounts = new ArrayList<>();
        for (long i = 1; i <= 10_000L; i++) {
            accounts.add(account(i, String.format("%08d", i), AccountType.SAVINGS, AccountStatus.ACTIVE,
                    BigDecimal.valueOf(i), owner));
        }
        when(accountRepository.findAllWithCustomer()).thenReturn(accounts);

        PreparedExport plan = exportService.prepare("accounts", "ops.user");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long start = System.nanoTime();
        exportService.write(plan, out);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        List<String> lines = lines(out.toString(StandardCharsets.UTF_8));
        assertThat(lines).hasSize(10_001);
        assertThat(lines.get(0)).isEqualTo("Account Number,Account Type,Status,Balance,Customer Reference");
        assertThat(elapsedMillis).isLessThan(10_000L);
        assertThat(savedAuditRow().getRecordCount()).isEqualTo(10_000L);
    }
}

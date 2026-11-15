package com.techverito.banking.service;

import com.techverito.banking.entity.ExportType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExportFormattingTest {

    /** Undoes the CSV quoting of a single field, so the spreadsheet sees the real cell. */
    private String unquote(String raw) {
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            return raw.substring(1, raw.length() - 1).replace("\"\"", "\"");
        }
        return raw;
    }

    @Test
    void customersHeader_isExactlyTheAgreedRow() {
        assertThat(ExportHeaders.CUSTOMERS).containsExactly(
                "Customer Reference", "Full Name", "Email", "Status", "Created Date");
        assertThat(CsvWriter.line(ExportHeaders.CUSTOMERS))
                .isEqualTo("Customer Reference,Full Name,Email,Status,Created Date");
    }

    @Test
    void accountsHeader_isExactlyTheAgreedRow() {
        assertThat(ExportHeaders.ACCOUNTS).containsExactly(
                "Account Number", "Account Type", "Status", "Balance", "Customer Reference");
        assertThat(CsvWriter.line(ExportHeaders.ACCOUNTS))
                .isEqualTo("Account Number,Account Type,Status,Balance,Customer Reference");
    }

    @Test
    void money_isAPlainAmountWithTwoDecimalsAndNoSymbol() {
        assertThat(ExportFormatting.money(new BigDecimal("1234.5"))).isEqualTo("1234.50");
        assertThat(ExportFormatting.money(BigDecimal.ZERO)).isEqualTo("0.00");
        assertThat(ExportFormatting.money(new BigDecimal("-12.3"))).isEqualTo("-12.30");
        assertThat(ExportFormatting.money(new BigDecimal("1234567.891"))).isEqualTo("1234567.89");
        assertThat(ExportFormatting.money(new BigDecimal("1234567.891")))
                .doesNotContain(",").doesNotContain("$").doesNotContain("€");
    }

    @Test
    void dateTime_isUtcInYyyyMmDdHhMm() {
        assertThat(ExportFormatting.dateTime(Instant.parse("2025-03-04T09:07:45Z")))
                .isEqualTo("2025-03-04 09:07");
        assertThat(ExportFormatting.dateTime(Instant.parse("2025-12-31T23:59:59Z")))
                .isEqualTo("2025-12-31 23:59");
    }

    @Test
    void accountNumberText_keepsLeadingZerosAsTextForSpreadsheets() {
        assertThat(ExportFormatting.accountNumberText("00012345")).isEqualTo("=\"00012345\"");

        String rawField = CsvWriter.line(List.of(ExportFormatting.accountNumberText("00012345")));
        assertThat(rawField).isEqualTo("\"=\"\"00012345\"\"\"");

        String cell = unquote(rawField);
        assertThat(cell).isEqualTo("=\"00012345\"");

        String plainAccountNumber = cell.replace("=", "").replace("\"", "");
        assertThat(plainAccountNumber).isEqualTo("00012345");
    }

    @Test
    void noRecordsNote_isExactlyTheAgreedText() {
        assertThat(ExportHeaders.NO_RECORDS).isEqualTo("No records found");
        assertThat(CsvWriter.line(List.of(ExportHeaders.NO_RECORDS))).isEqualTo("No records found");
    }

    @Test
    void exportType_exposesExactlyCustomersAccountsAndBoth() {
        assertThat(ExportType.values())
                .containsExactly(ExportType.CUSTOMERS, ExportType.ACCOUNTS, ExportType.BOTH);

        assertThat(ExportType.CUSTOMERS.includesCustomers()).isTrue();
        assertThat(ExportType.CUSTOMERS.includesAccounts()).isFalse();
        assertThat(ExportType.ACCOUNTS.includesCustomers()).isFalse();
        assertThat(ExportType.ACCOUNTS.includesAccounts()).isTrue();
        assertThat(ExportType.BOTH.includesCustomers()).isTrue();
        assertThat(ExportType.BOTH.includesAccounts()).isTrue();
    }
}

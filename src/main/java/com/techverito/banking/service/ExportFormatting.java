package com.techverito.banking.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * How a single value appears in the exported file: money, dates and the
 * text-protected account number. Everything is rendered in UTC.
 */
public final class ExportFormatting {

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);

    private ExportFormatting() {
    }

    /**
     * A plain amount with two decimals: no currency symbol and no grouping
     * separator (AC4).
     */
    public static String money(BigDecimal amount) {
        BigDecimal value = (amount != null) ? amount : BigDecimal.ZERO;
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /**
     * {@code YYYY-MM-DD HH:MM} in UTC (AC4).
     */
    public static String dateTime(Instant instant) {
        return (instant != null) ? DATE_TIME.format(instant) : "";
    }

    /**
     * Wraps the account number in a spreadsheet text formula, so a value such as
     * {@code 00012345} keeps its leading zeros and is not turned into a number by
     * Excel or Google Sheets (AC7). In the raw file the cell appears quoted as
     * {@code "=""00012345"""}.
     */
    public static String accountNumberText(String accountNumber) {
        return "=\"" + ((accountNumber != null) ? accountNumber : "") + "\"";
    }
}

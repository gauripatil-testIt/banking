package com.techverito.banking.service;

import java.util.List;

/**
 * The header rows and the empty-result note of the CSV export. The names are part
 * of the exported contract: existing reports and formulas depend on them, so they
 * must not change between releases (AC5).
 */
public final class ExportHeaders {

    public static final List<String> CUSTOMERS = List.of(
            "Customer Reference",
            "Full Name",
            "Email",
            "Status",
            "Created Date");

    public static final List<String> ACCOUNTS = List.of(
            "Account Number",
            "Account Type",
            "Status",
            "Balance",
            "Customer Reference");

    /** Written as a single-cell row when a section has no records (AC8). */
    public static final String NO_RECORDS = "No records found";

    private ExportHeaders() {
    }
}

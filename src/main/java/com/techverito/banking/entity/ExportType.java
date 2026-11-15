package com.techverito.banking.entity;

/**
 * What an export contains. The wire values are the lower-case forms accepted by
 * the {@code type} query parameter of the export endpoint.
 */
public enum ExportType {

    CUSTOMERS("customers"),
    ACCOUNTS("accounts"),
    BOTH("both");

    private final String wireValue;

    ExportType(String wireValue) {
        this.wireValue = wireValue;
    }

    public String getWireValue() {
        return wireValue;
    }

    public boolean includesCustomers() {
        return this == CUSTOMERS || this == BOTH;
    }

    public boolean includesAccounts() {
        return this == ACCOUNTS || this == BOTH;
    }
}

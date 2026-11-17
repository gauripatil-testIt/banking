package com.techverito.banking.event;

import java.util.Optional;

/**
 * The business event types this application announces.
 *
 * <p>The wire value is the lower-case dotted name carried on the envelope (for
 * example {@code customer.registered}), following the
 * {@link com.techverito.banking.entity.ExportType#getWireValue()} convention, so the
 * JSON stays the documented format instead of exposing an enum name. The current
 * version is emitted on new events; a breaking payload change gets a new version
 * rather than redefining this one.</p>
 */
public enum BusinessEventType {

    CUSTOMER_REGISTERED("customer.registered", 1),
    ACCOUNT_OPENED("account.opened", 1),
    ACCOUNT_STATUS_CHANGED("account.status.changed", 1),
    DOCUMENT_UPLOADED("document.uploaded", 1),
    EXPORT_REQUESTED("export.requested", 1),
    STATEMENT_GENERATED("statement.generated", 1),
    WELCOME_EMAIL_REQUESTED("welcome.email.requested", 1),
    STATEMENT_REQUESTED("statement.requested", 1),
    DOCUMENT_SCAN_REQUESTED("document.scan.requested", 1);

    private final String wireValue;
    private final int currentVersion;

    BusinessEventType(String wireValue, int currentVersion) {
        this.wireValue = wireValue;
        this.currentVersion = currentVersion;
    }

    public String getWireValue() {
        return wireValue;
    }

    /** The version emitted for new events of this type. */
    public int getCurrentVersion() {
        return currentVersion;
    }

    /** Looks a wire value up, returning an empty Optional for null or an unknown value. */
    public static Optional<BusinessEventType> fromWireValue(String wireValue) {
        if (wireValue == null) {
            return Optional.empty();
        }
        for (BusinessEventType candidate : values()) {
            if (candidate.wireValue.equals(wireValue)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}

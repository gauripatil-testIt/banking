package com.techverito.banking.event;

/**
 * A statement was generated. Declared only: no flow publishes this yet, the
 * statement feature does not exist.
 */
public record StatementGeneratedPayload(
        Long accountId,
        String statementPeriod
) implements EventPayload {

    @Override
    public EventType eventType() {
        return EventType.STATEMENT_GENERATED;
    }
}

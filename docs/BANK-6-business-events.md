# BANK-6 – The business event format

Every business change the application announces is published in **one** format: a
`BusinessEvent` envelope carrying a typed payload. Reporting, audit and notifications subscribe to
that one format instead of each team wiring its own integration.

Business code publishes and subscribes through `com.techverito.banking.event.EventBus` only. No
controller or service refers to a broker class, and which `EventBus` implementation runs is decided
by configuration.

## The envelope

`BusinessEvent` is a record with these fields:

| Field           | Type          | Meaning                                                                  |
| --------------- | ------------- | ------------------------------------------------------------------------ |
| `eventId`       | `String`      | unique id of this occurrence (a UUID), minted by `BusinessEvent.of`        |
| `type`          | `EventType`   | one of the six business event types below                                 |
| `version`       | `int`         | format version of the payload, taken from the payload                     |
| `occurredAt`    | `Instant`     | when the change happened                                                  |
| `customerId`    | `Long`        | the customer the event concerns, when it concerns one                     |
| `accountId`     | `Long`        | the account the event concerns, when it concerns one                      |
| `actor`         | `String`      | who or what caused the change, stamped on publish                        |
| `correlationId` | `String`      | ties every event of one HTTP request together, stamped on publish        |
| `payload`       | `EventPayload`| the typed body, one record per event type                                |

`customerId` and `accountId` are nullable: an event concerns a customer or an account, sometimes
both. The envelope is built with `BusinessEvent.of(payload, customerId, accountId)`, which mints a
new `eventId`, takes `occurredAt` from the clock, and derives `type` and `version` from the payload.
`actor` and `correlationId` are filled in by the `EventBus` when the event is published, so a caller
cannot forget them.

An example event body, as JSON:

```json
{
  "eventId": "3f1c4f3e-3a1b-4c8e-9d0f-2b6f9c1e7a55",
  "type": "DOCUMENT_UPLOADED",
  "version": 1,
  "occurredAt": "2025-01-01T10:15:30.123456Z",
  "customerId": 1,
  "accountId": null,
  "actor": "ops.user",
  "correlationId": "8e2b7c44-1f6d-4a3b-9c5e-0d7a2b4c6e18",
  "payload": {
    "documentId": 7,
    "documentType": "ID_PROOF",
    "originalFileName": "sample-valid.pdf",
    "sizeBytes": 650,
    "uploadedBy": "ops.user"
  }
}
```

## The six event types

`EventType` has exactly these constants. There is no "unknown" fallback: a new business event is a
new constant plus a new payload record.

| `EventType`              | Payload record               | Payload fields                                                        | Published today |
| ------------------------ | ---------------------------- | --------------------------------------------------------------------- | --------------- |
| `CUSTOMER_REGISTERED`    | `CustomerRegisteredPayload`  | `customerId`, `email`, `status`                                       | yes – `CustomerService.create` |
| `ACCOUNT_OPENED`         | `AccountOpenedPayload`       | `accountId`, `customerId`, `accountNumber`, `type`, `status`          | yes – `AccountService.create` |
| `ACCOUNT_STATUS_CHANGED` | `AccountStatusChangedPayload`| `accountId`, `previousStatus`, `newStatus`                            | yes – `AccountService.update`, only when the status actually changes |
| `DOCUMENT_UPLOADED`      | `DocumentUploadedPayload`    | `documentId`, `documentType`, `originalFileName`, `sizeBytes`, `uploadedBy` | yes – `DocumentService.upload` |
| `EXPORT_REQUESTED`       | `ExportRequestedPayload`     | `customerId`, `exportType`                                            | no – declared only, the export flow does not exist yet |
| `STATEMENT_GENERATED`    | `StatementGeneratedPayload`  | `accountId`, `statementPeriod`                                        | no – declared only, the statement flow does not exist yet |

## Actor and correlation id

- **Actor** – the application has no authentication, so the actor is an explicit argument of
  `EventBus.publish(event, actor)`, supplied by the caller. Flows with an existing user field use it:
  the document upload passes `uploadedBy`. The other flows pass
  `EventRequestContext.actor()`, which is the `X-Actor` request header and falls back to `"system"`
  when the header is absent or blank. A null or blank actor is rejected by the bus.
- **Correlation id** – `EventRequestContextFilter` generates exactly one UUID per HTTP request, puts
  it in the request-scoped `EventRequestContext` together with the actor, and echoes it back in the
  `X-Correlation-Id` response header. Every event published during that request carries the same
  correlation id. The context is cleared at the end of the request, so nothing leaks between
  requests. An event published with no request in flight (for example from a test or a scheduled
  job) still gets a non-null correlation id, minted by the bus.

## Versioning

- `EventPayload.version()` returns `1` for every payload declared today.
- **Adding a field keeps the current version.** An existing consumer deserialises a payload that has
  an extra field without breaking, as long as it ignores unknown JSON properties (Spring Boot's
  ObjectMapper is configured that way by default).
- **A breaking change ships a new version.** Removing or reshaping a field is expressed by a payload
  whose `version()` returns `2` (a new payload record), so consumers can tell the two shapes apart.
  An existing payload's field set is never changed in place.

## Configuration

The implementation is selected by one property:

```properties
banking.events.bus=in-memory
```

`in-memory` is the only supported value today; it is also the default when the property is absent.
Any other value (for example `rabbit`) makes the application fail to start with an error naming the
configured value, rather than silently falling back. Broker adapters drop in behind the same switch
and change no business code.

## In-memory bus: local development and tests only

`InMemoryEventBus` keeps subscribers in memory and delivers synchronously, deferring delivery until
the surrounding transaction commits so an event is never announced for work that rolled back. It
loses everything when the JVM stops and nothing leaves the process.

It exists so the application and the automated tests run with no broker installed. Outside local
development and automated tests the configured implementation must be a real broker. Durable
delivery to a real broker, retries and dead-letter handling are **BANK-9**, not part of this format.

## How to publish and subscribe

Publish after the change has been saved, passing the actor explicitly:

```java
eventBus.publish(BusinessEvent.of(
        new CustomerRegisteredPayload(saved.getId(), saved.getEmail(), saved.getStatus()),
        saved.getId(), null), requestContext.actor());
```

Subscribe to one event type; the returned handle cancels the subscription:

```java
EventBus.Subscription subscription = eventBus.subscribe(EventType.CUSTOMER_REGISTERED,
        event -> handleCustomerRegistered(event));

// later, e.g. on shutdown
subscription.cancel();
```

A subscriber that throws is logged with the event id and type; it does not stop the other
subscribers and the exception does not propagate to the publisher.

A new business flow adds a `EventType` constant, a payload record implementing `EventPayload`, and a
publish call through `EventBus`. It does not add a broker client.

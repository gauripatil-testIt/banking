# BANK-6 – The business event format, its versions and how the bus is selected

Every business change this application announces travels in **one** envelope, written as JSON by one
codec (`BusinessEventJson`), published through one interface (`BusinessEventBus`) and subscribed to
through the same interface. Reporting, audit and notifications can therefore plug in without each
team inventing its own integration.

This page documents the format field by field, the nine event types and their payloads, one complete
example, the compatibility rules that keep existing consumers working, and the configuration that
chooses the implementation.

---

## 1. The envelope

`com.techverito.banking.event.BusinessEvent` is the only envelope. Its record components are the wire
fields, in this order:

| Field           | Java type          | Required | Meaning                                                                              |
| --------------- | ------------------ | -------- | ------------------------------------------------------------------------------------ |
| `eventId`       | `java.util.UUID`   | yes      | Unique id of this announcement, stamped once when the event is created. Consumers deduplicate on it. |
| `type`          | `String`           | yes      | Lower-case dotted event type, for example `customer.registered`. Never the enum name.  |
| `version`       | `Integer`          | yes      | Version of that type's payload, `1` today. Must be `1` or greater.                     |
| `occurredAt`    | `java.time.Instant`| yes      | When the change happened, from the application `Clock`. Written as an ISO-8601 instant. |
| `subjectType`   | `SubjectType`      | yes      | What the event concerns: `CUSTOMER` or `ACCOUNT`.                                      |
| `subjectId`     | `Long`             | yes      | The id of that customer or account. `subjectType` + `subjectId` is the subject pair.   |
| `causedBy`      | `String`           | yes      | Who or what caused the change, for example `ops.user` or `system`. Must not be blank.  |
| `correlationId` | `String`           | yes      | Ties every event of one operation or request together. Must not be blank.              |
| `payload`       | `T extends EventPayload` | yes | The type-specific body: one record per event type, see section 2.                      |

Every field is required. The envelope rejects an incomplete event when it is constructed, with
`com.techverito.banking.exception.BusinessEventValidationException`: a null `eventId`, a blank `type`,
a `version` that is null or below 1, a null `occurredAt`, a null `subjectType` or `subjectId`, a blank
`causedBy` or `correlationId`, or a null `payload`. An event that exists at all is therefore complete,
and no code path can emit an event without a correlation id.

Payloads are the records behind the sealed marker `EventPayload`; that interface permits exactly the
nine payloads in section 2 and nothing else. The envelope is immutable (a record, no setters).

### Producing an event

Business code does not assemble the envelope by hand. `BusinessEventFactory.create(...)` stamps the
parts an announcer must not choose:

* `eventId` – a fresh `UUID.randomUUID()` per call, so two calls never share an id.
* `occurredAt` – `Instant.now(clock)` from the shared `Clock` bean (`ClockConfig`), so a test can fix
  the time.
* `type` and `version` – taken from the `BusinessEventType`, so the wire value and the current version
  can never disagree with the enum.

`subjectType`, `subjectId`, `causedBy`, `correlationId` and the payload are passed straight through.

---

## 2. Event types and payloads

`com.techverito.banking.event.BusinessEventType` declares exactly nine types. Each carries a
lower-case dotted **wire value** – the string that appears in the JSON `type` field – and a current
version:

| Enum constant               | Wire value (`type`)       | Version | Payload record                  |
| --------------------------- | ------------------------- | ------- | ------------------------------- |
| `CUSTOMER_REGISTERED`       | `customer.registered`     | 1       | `CustomerRegisteredPayload`     |
| `ACCOUNT_OPENED`            | `account.opened`          | 1       | `AccountOpenedPayload`          |
| `ACCOUNT_STATUS_CHANGED`    | `account.status.changed`  | 1       | `AccountStatusChangedPayload`   |
| `DOCUMENT_UPLOADED`         | `document.uploaded`       | 1       | `DocumentUploadedPayload`       |
| `EXPORT_REQUESTED`          | `export.requested`        | 1       | `ExportRequestedPayload`        |
| `STATEMENT_GENERATED`       | `statement.generated`     | 1       | `StatementGeneratedPayload`     |
| `WELCOME_EMAIL_REQUESTED`   | `welcome.email.requested` | 1       | `WelcomeEmailRequestedPayload`  |
| `STATEMENT_REQUESTED`       | `statement.requested`     | 1       | `StatementRequestedPayload`     |
| `DOCUMENT_SCAN_REQUESTED`   | `document.scan.requested` | 1       | `DocumentScanRequestedPayload`  |

`BusinessEventType.fromWireValue(String)` looks a wire value up, is null-safe and returns an empty
`Optional` for an unknown value. `getCurrentVersion()` returns the version emitted for **new** events
of that type; it is `1` for all nine today.

The last three types are the task-submission events: they name work that is handed to a background
worker rather than a change that has already happened, so `STATEMENT_REQUESTED` exists alongside
`STATEMENT_GENERATED` and a scan request alongside `DOCUMENT_UPLOADED`.

### Payload fields

Each payload reuses the existing domain enums (`AccountType`, `AccountStatus`, `DocumentType`,
`ExportType`) the way the REST DTOs already do.

| Payload                       | Fields                                                                                                    | Rejects                             |
| ----------------------------- | --------------------------------------------------------------------------------------------------------- | ----------------------------------- |
| `CustomerRegisteredPayload`   | `customerId` (`Long`), `firstName` (`String`), `lastName` (`String`), `email` (`String`)                   | null `customerId`, blank `email`     |
| `AccountOpenedPayload`        | `accountId` (`Long`), `customerId` (`Long`), `accountNumber` (`String`), `accountType` (`AccountType`), `openingBalance` (`BigDecimal`) | null `accountId`, null `customerId`, blank `accountNumber` |
| `AccountStatusChangedPayload` | `accountId` (`Long`), `accountNumber` (`String`), `previousStatus` (`AccountStatus`), `newStatus` (`AccountStatus`) | null `accountId`, null `previousStatus`, null `newStatus` |
| `DocumentUploadedPayload`     | `documentId` (`Long`), `customerId` (`Long`), `documentType` (`DocumentType`), `originalFileName` (`String`), `sizeBytes` (`long`), `uploadedBy` (`String`) | null `documentId`, null `documentType` |
| `ExportRequestedPayload`      | `exportType` (`ExportType`), `exportedBy` (`String`), `fileName` (`String`)                                 | null `exportType`, blank `exportedBy` |
| `StatementGeneratedPayload`   | `statementId` (`Long`), `accountId` (`Long`), `periodStart` (`LocalDate`), `periodEnd` (`LocalDate`), `format` (`String`) | null `statementId`, null `accountId` |
| `WelcomeEmailRequestedPayload` | `customerId` (`Long`), `email` (`String`), `requestedBy` (`String`)                                       | null `customerId`, blank `email`, blank `requestedBy` |
| `StatementRequestedPayload`   | `accountId` (`Long`), `periodStart` (`LocalDate`), `periodEnd` (`LocalDate`), `requestedBy` (`String`)     | null `accountId`, null `periodStart`, null `periodEnd`, blank `requestedBy`, `periodStart` after `periodEnd` |
| `DocumentScanRequestedPayload` | `documentId` (`Long`), `customerId` (`Long`), `originalFileName` (`String`)                               | null `documentId`, null `customerId` |

A rejected payload also throws `BusinessEventValidationException`, naming the missing field, so an
announcement without its identifying id is impossible.

---

## 3. Example JSON

Exactly what `BusinessEventJson.toJson(...)` writes for a `customer.registered` event (indented for
reading; the codec writes a single line):

```json
{
  "eventId": "3f1d2c9a-8b7e-4c6d-9f01-2a3b4c5d6e7f",
  "type": "customer.registered",
  "version": 1,
  "occurredAt": "2025-03-04T09:07:45Z",
  "subjectType": "CUSTOMER",
  "subjectId": 1,
  "causedBy": "ops.user",
  "correlationId": "corr-2025-03-04-0001",
  "payload": {
    "customerId": 1,
    "firstName": "John",
    "lastName": "Doe",
    "email": "john@example.com"
  }
}
```

`type` is the lowercase dotted string, `version` is a JSON number, `eventId` is a UUID string,
`occurredAt` is an ISO-8601 instant string, `subjectType` is the enum constant name, and `payload` is
a nested object whose fields are the payload record's components.

### Reading an event

`BusinessEventJson` owns its own `ObjectMapper` (JavaTimeModule, ISO-8601 dates, unknown properties
ignored) and is the only place that converts between the envelope and JSON. A reader supplies the
concrete payload class, so no Jackson type metadata and no type registry is involved:

```java
BusinessEvent<CustomerRegisteredPayload> event =
        businessEventJson.fromJson(json, CustomerRegisteredPayload.class);
```

The reader decides the payload shape; the envelope needs no `@JsonTypeInfo`. That is what keeps a
version bump additive.

---

## 4. Compatibility and versioning

The rules, in the order a change is decided:

* **Adding a field is compatible.** An added envelope or payload field does not break an existing
  consumer: `BusinessEventJson` is configured with `FAIL_ON_UNKNOWN_PROPERTIES` disabled, so a
  consumer of an older shape simply ignores fields it does not declare. Do not reuse a field name for
  a new meaning.
* **Removing a field, re-typing a field or changing a field's meaning is a breaking change.** It
  requires a **new version** of the same event type: the version is carried on every event, so old
  and new shapes coexist while consumers migrate. Never redefine the existing version.
* **The version lives on the envelope, not in the type string.** A v2 `customer.registered` is still
  `"type": "customer.registered"` with `"version": 2`; a consumer reads `version` and branches
  instead of failing to deserialise.
* **Subscribe to a version explicitly.** `BusinessEventBus.subscribe(type, version, payloadType,
  handler)` matches the type's wire value and the exact version, so a v1 handler is never handed a v2
  event. That is what makes the rule enforceable rather than aspirational.
* **New event types are additive.** They are new dotted wire values with version 1; existing
  consumers ignore them.
* An event the codec cannot parse at all is rejected loudly with
  `BusinessEventValidationException`; an event it parses but does not understand is not silently
  dropped: check `BusinessEventType.fromWireValue(...)`, which returns an empty `Optional` for an
  unknown type.

---

## 5. Configuration: which implementation runs

One interface, two implementations, chosen by configuration with no change to business code:

| Implementation               | Selected when                                                   |
| ---------------------------- | --------------------------------------------------------------- |
| `KafkaBusinessEventBus`      | `banking.events.bus=kafka`, **the default when the property is absent** |
| `InMemoryBusinessEventBus`   | `banking.events.bus=in-memory`                                   |

| Property                                 | Environment variable                     | Default           | Meaning                                                     |
| ---------------------------------------- | ---------------------------------------- | ----------------- | ----------------------------------------------------------- |
| `banking.events.bus`                     | `BANKING_EVENTS_BUS`                     | `kafka`           | `kafka` or `in-memory` (`EventBusType`).                     |
| `banking.events.topic`                   | `BANKING_EVENTS_TOPIC`                   | `banking.events`  | The topic events are published to.                           |
| `banking.events.kafka.bootstrap-servers`  | `BANKING_EVENTS_KAFKA_BOOTSTRAP_SERVERS` | none – required   | Broker address; startup fails with a readable message if unset while the bus is Kafka. |
| `banking.events.kafka.client-id`          | `BANKING_EVENTS_KAFKA_CLIENT_ID`         | `banking-service` | Producer `client.id`.                                        |

The Kafka producer is configured with `StringSerializer` for key and value, `acks=all` and
`enable.idempotence=true`, so a retried send cannot duplicate an event within the producer session.
The partition key of an event is its subject (`<subjectType>-<subjectId>`), so every event about one
customer or one account lands on the same partition in order.

`src/main/resources/application.properties` sets `banking.events.bus=in-memory` and
`banking.events.topic=banking.events` **for local development only**. A deployment does not set the
bus, gets the Kafka default, and refuses to start if `banking.events.kafka.bootstrap-servers` is
missing – it cannot silently drop events.

### The in-memory bus is for local development and automated tests only

`InMemoryBusinessEventBus` delivers synchronously, in subscription order, inside this process, and
logs a startup warning saying so. It is for a workstation and for the automated test suite, where no
broker is installed. Outside those environments the configured implementation **must** be the Kafka
one: the in-memory bus delivers nothing once the application reaches another process, so anything
depending on the event (reporting, audit, notifications) would silently see nothing.

A handler that throws is logged at error level with the `eventId`, `type` and `correlationId`, and
does not stop delivery to the other subscribers or fail the publisher – the in-memory bus stands in
for a broker, which would also isolate one bad consumer.

---

## 6. Scope of this story, and what comes next

BANK-6 delivers the **format**, the **event types** and their payloads, the **one
publishing/subscribing interface** (`BusinessEventBus`, `BusinessEventHandler`, `EventSubscription`),
the **event factory**, the **JSON codec**, the **in-memory bus**, the **Spring Kafka producer
adapter** and the configuration selection above. The doc you are reading is part of that delivery.

Not in this story:

* **Publishing from existing business code.** `CustomerService`, `AccountService`, `DocumentService`
  and `ExportService` are unchanged: nothing is announced yet. Wiring the six events into those flows
  is a follow-up story.
* **Reliable delivery and consumption.** No outbox, no retries, no dead-letter queue, no
  `@KafkaListener`, no idempotent consumer, no ordering guarantee beyond per-key partitioning.
  Publishing to Kafka is fire-and-forget: a failed send is logged with the `eventId`, `type` and
  `correlationId` (never a customer name or email) and is not rethrown, so an announcement can never
  fail a business operation. `KafkaBusinessEventBus.subscribe(...)` throws
  `UnsupportedOperationException` naming BANK-9, because a handler accepted now would never be called.
  **Reliable delivery to a real broker and consumption are BANK-9.**
* **A statement generation feature.** Only the `statement.generated` type and payload are defined here;
  there is no statement entity, table or endpoint.
* **Persistence of events.** No table, no migration: the event model is the envelope plus JSON.
* **Other brokers.** No RabbitMQ/AMQP adapter, and no schema registry, Avro or Protobuf.

### Added after BANK-6

BANK-8 added the three task-submission types – `welcome.email.requested`, `statement.requested` and
`document.scan.requested` – with their payloads, so a background task can be announced in this same
envelope, plus a RabbitMQ task-queue producer alongside the Kafka bus. It also started submitting
those tasks from the existing customer-creation and document-upload flows and added a statement
*submission* endpoint (`POST /accounts/{accountId}/statements`) that queues the request. The envelope,
the codec, the factory and the bus implementations described above are unchanged by that addition,
and no statement is generated or stored: it is still only a request.

---

## Sign-off

| # | Check                                                                       | Result |
| - | --------------------------------------------------------------------------- | ------ |
| 1 | Every envelope field documented with its Java type and marked required      | ☐      |
| 2 | Nine event types listed with exact wire values, version 1 and payload fields | ☐      |
| 3 | One complete example whose field names and `type` value match the codec      | ☐      |
| 4 | Compatible-change and new-version rules stated                              | ☐      |
| 5 | Configuration properties, defaults and in-memory-only warning stated        | ☐      |
| 6 | Scope stated, with reliable delivery and consumption deferred to BANK-9     | ☐      |

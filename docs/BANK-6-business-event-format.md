# BANK-6 – One business event format: reference and verification guide

Every business change this application announces uses one envelope and one set of
six event types, whatever carries it. Reporting, audit and notification plug into
the envelope once instead of agreeing an integration each.

This guide is the format of record: the envelope's fields, the six event types
with a copy-pasteable sample each, the versioning rule, the configuration that
chooses the transport, the one environment in which the in-memory bus may be used,
and the commands that verify the story.

**Business flows do not publish yet.** BANK-6 delivers the format, the single
`BusinessEventBus` interface, the in-memory bus, the Kafka adapter and their tests.
`CustomerService`, `AccountService`, `DocumentService` and `ExportService` are
wired to publish in later stories (BANK-7 and after), so registering a customer
today produces no event. There is also no HTTP endpoint and no database table for
events: BANK-6 keeps events in flight only.

---

## 1. The envelope

Every event is one JSON object with exactly these fields, in this order. `null`
fields are omitted from the payload (`@JsonInclude(NON_NULL)`), so an event that
concerns only a customer carries no `accountId` key at all.

| Field           | JSON type        | Required | Meaning                                                                                     |
| --------------- | ---------------- | -------- | ------------------------------------------------------------------------------------------- |
| `eventId`       | string (UUID)    | yes      | Unique id of this event, assigned by its publisher. Never reused, never blank.               |
| `type`          | string (enum)    | yes      | Which of the six business changes this is – see section 2.                                   |
| `version`       | number (int)     | yes      | Format version of the envelope, at least `1`. Today's producers write `1` – see section 3.    |
| `occurredAt`    | string (ISO-8601 UTC) | yes | When the business change happened, from the application's `Clock` bean.                    |
| `customerId`    | number (long)    | no       | The customer the event concerns. Nullable, but at least one of `customerId`/`accountId` is required. |
| `accountId`     | number (long)    | no       | The account the event concerns. Nullable, but at least one of `customerId`/`accountId` is required. |
| `actor`         | string           | yes      | Who or what caused the event: an operator name (`ops.user`) or a system component.           |
| `correlationId` | string           | yes      | Ties one business request together across events and logs; supplied by the caller.           |

The rules above are enforced in the `BusinessEvent` record's compact constructor,
not in a separate validator, so an event that breaks one cannot be constructed. An
event naming neither a customer nor an account is rejected by every transport with
an `IllegalArgumentException` whose message is exactly

```
An event must reference what it concerns: at least one of customerId or accountId is required.
```

`BusinessEvent.create(type, customerId, accountId, actor, correlationId, clock)`
fills `eventId` from a random UUID, `occurredAt` from the supplied `Clock` and
`version` from `BusinessEvent.CURRENT_VERSION`, so no publisher invents its own
identifiers or timestamps.

Two Java types carry the format:

| Type                                                     | Purpose                                                                              |
| -------------------------------------------------------- | ------------------------------------------------------------------------------------ |
| `com.techverito.banking.event.BusinessEvent`              | The envelope record above; validates itself and serialises to the JSON in section 2.  |
| `com.techverito.banking.event.EventType`                  | The six event types; the constant name **is** the wire value, with no transformation. |

---

## 2. The six event types

`type` is one of exactly six enum constants, serialised verbatim – `"type":
"DOCUMENT_UPLOADED"`, never `"documentUploaded"`.

| Type                     | What it announces                                    | Identifier(s)                         |
| ------------------------ | ---------------------------------------------------- | ------------------------------------- |
| `CUSTOMER_REGISTERED`    | a new customer was registered                        | `customerId`                          |
| `ACCOUNT_OPENED`         | an account was opened                                | `accountId` (usually `customerId` too) |
| `ACCOUNT_STATUS_CHANGED` | an account moved between `ACTIVE`, `INACTIVE`, `CLOSED` | `accountId` and `customerId`       |
| `DOCUMENT_UPLOADED`      | a customer document was stored                       | `customerId`                          |
| `EXPORT_REQUESTED`       | a CSV export was prepared for someone                | `customerId`                          |
| `STATEMENT_GENERATED`    | a statement was generated for an account             | `accountId` and `customerId`          |

The samples below are the exact payloads the envelope produces. Nothing in them is
a placeholder: `eventId` is a UUID, `occurredAt` is an ISO-8601 UTC instant, and a
field absent from the object was null for that event.

### 2.1 `CUSTOMER_REGISTERED`

```json
{
  "eventId": "0b3b3f1e-6c1a-4a5b-9b0e-2b1f1b8a9c11",
  "type": "CUSTOMER_REGISTERED",
  "version": 1,
  "occurredAt": "2025-03-04T09:07:45Z",
  "customerId": 1,
  "actor": "ops.user",
  "correlationId": "corr-customer-1"
}
```

### 2.2 `ACCOUNT_OPENED`

```json
{
  "eventId": "5d2f7a44-9f0e-4a6d-8f3a-7c6d5e4b3a21",
  "type": "ACCOUNT_OPENED",
  "version": 1,
  "occurredAt": "2025-03-04T09:08:10Z",
  "customerId": 1,
  "accountId": 9,
  "actor": "ops.user",
  "correlationId": "corr-account-open-9"
}
```

### 2.3 `ACCOUNT_STATUS_CHANGED`

```json
{
  "eventId": "9a8b7c6d-5e4f-4a3b-8c2d-1e0f9a8b7c6d",
  "type": "ACCOUNT_STATUS_CHANGED",
  "version": 1,
  "occurredAt": "2025-03-04T09:15:00Z",
  "customerId": 1,
  "accountId": 9,
  "actor": "ops.user",
  "correlationId": "corr-account-status-9"
}
```

### 2.4 `DOCUMENT_UPLOADED`

```json
{
  "eventId": "c1d2e3f4-a5b6-4c7d-8e9f-0a1b2c3d4e5f",
  "type": "DOCUMENT_UPLOADED",
  "version": 1,
  "occurredAt": "2025-03-04T09:20:30Z",
  "customerId": 1,
  "actor": "ops.user",
  "correlationId": "corr-document-7"
}
```

### 2.5 `EXPORT_REQUESTED`

```json
{
  "eventId": "77e6d5c4-b3a2-4190-8f7e-6d5c4b3a2918",
  "type": "EXPORT_REQUESTED",
  "version": 1,
  "occurredAt": "2025-03-04T09:30:00Z",
  "customerId": 1,
  "actor": "ops.user",
  "correlationId": "corr-export-2025-03-04"
}
```

### 2.6 `STATEMENT_GENERATED`

```json
{
  "eventId": "3f4e5d6c-7b8a-4991-8a2b-3c4d5e6f7a8b",
  "type": "STATEMENT_GENERATED",
  "version": 1,
  "occurredAt": "2025-03-04T09:35:12Z",
  "customerId": 1,
  "accountId": 9,
  "actor": "system.statement-scheduler",
  "correlationId": "corr-statement-9-2025-03"
}
```

---

## 3. Versioning: additive fields are safe, breaking changes are not

The envelope carries its own `version` so a consumer can read a payload it was not
written for.

- **Adding a field does not break existing consumers.** The envelope is annotated
  `@JsonIgnoreProperties(ignoreUnknown = true)`, so a consumer built against version
  `1` reads a payload that carries an extra field and ignores it; every declared
  field comes through unchanged. Add a new **optional** field and keep
  `BusinessEvent.CURRENT_VERSION` as it is.
- **A breaking change needs a new event version.** Removing a field, renaming one,
  changing its type or its meaning increments `version` (and
  `BusinessEvent.CURRENT_VERSION`), and consumers branch on the `version` value they
  receive. Producing two versions side by side while consumers migrate is the
  intended path; silently redefining a field is not.
- `version` is carried in the payload, not inferred: a payload with `"version": 2`
  deserialises with `version` 2.

---

## 4. Configuration: which implementation is used

Business code publishes and subscribes through one interface,
`com.techverito.banking.event.BusinessEventBus`, whose only methods are
`publish(BusinessEvent)` and `subscribe(EventType, Consumer<BusinessEvent>)`. No
controller or service refers to a Kafka or RabbitMQ class – a source-scanning test
keeps it that way – so switching transport is a property change, never a code
change.

| Property                        | Environment variable            | Default                    | Meaning                                                        |
| ------------------------------- | ------------------------------- | -------------------------- | -------------------------------------------------------------- |
| `banking.events.transport`      | `BANKING_EVENTS_TRANSPORT`      | `KAFKA`                    | Which implementation is used: `KAFKA` or `IN_MEMORY`.            |
| `banking.events.topic`          | `BANKING_EVENTS_TOPIC`          | `banking.business-events`  | The Kafka topic every business event is published to.            |
| `banking.events.consumer-group` | `BANKING_EVENTS_CONSUMER_GROUP` | `banking-event-subscribers`| The consumer group this application's subscribers share.         |

`BANKING_EVENTS_TRANSPORT` accepts exactly the two values `KAFKA` and `IN_MEMORY`
(Spring's relaxed binding also accepts `kafka` / `in-memory`). None of these values
is set in `application.properties`: the default is `KAFKA`, so a deployment that
forgets the setting gets the real broker rather than keeping events inside one JVM.

| Transport | Implementation                                     | When it may be used                                        |
| --------- | -------------------------------------------------- | ---------------------------------------------------------- |
| `KAFKA`   | `com.techverito.banking.event.kafka.KafkaEventBus` | anywhere, and the default in every environment             |
| `IN_MEMORY` | `com.techverito.banking.event.InMemoryEventBus`  | **local development and automated tests only**              |

### The Kafka adapter

`KafkaEventBus` publishes through `KafkaTemplate<String, BusinessEvent>` to
`banking.events.topic`, keyed by what the event concerns – `customer:{customerId}`
when a customer is named, `account:{accountId}` otherwise – so the events of one
customer stay in order on one partition. Its one `@KafkaListener` consumes the same
topic in the configured consumer group and hands each event to the handlers
registered through `subscribe(...)` for its type. Payloads are JSON
(`JsonSerializer` / `JsonDeserializer`) with type headers turned off, and the
trusted package for consumption is `com.techverito.banking.event`.
`KafkaEventConfig` declares `@EnableKafka`, the producer and consumer factories, the
listener container factory and the `NewTopic`, and is only active for the Kafka
transport – so `IN_MEMORY` creates no producer, consumer or topic and never contacts
a broker.

A handler that throws is logged with the `eventId`, `type` and `correlationId` of the
failing event and the remaining handlers for that event still run. **Retry,
dead-lettering, idempotency by `eventId` and publish/consume metrics are BANK-9**,
not this story: BANK-6 adds no `DefaultErrorHandler`, no dead-letter topic and no
outbox.

### The in-memory bus

`InMemoryEventBus` delivers synchronously on the publishing thread to the handlers
registered for the event's type, so a test can publish and assert immediately
afterwards. It exists for local development and automated tests only, and the bean
refuses to be created unless the `local` or `test` profile is active. Selecting it
without one of those profiles fails startup with a message naming both profiles and
`banking.events.transport`.

---

## 5. Running in-memory locally

Select the in-memory transport **with the `local` profile active** – without the
profile the guard above refuses to start:

```bash
BANKING_EVENTS_TRANSPORT=IN_MEMORY \
SPRING_PROFILES_ACTIVE=local \
BANKING_DOCUMENTS_STORAGE=local \
mvn spring-boot:run
```

`BANKING_DOCUMENTS_STORAGE=local` is there only because document storage defaults to
S3 and refuses to start without a bucket and region (see
`docs/BANK-5-s3-document-storage-deployment-guide.md`); it is unrelated to
eventing. With the in-memory bus selected, no broker is contacted at all.

To reproduce the guard, drop the profile:

```bash
BANKING_EVENTS_TRANSPORT=IN_MEMORY mvn spring-boot:run
```

- [ ] Startup fails with a message naming the `local` and `test` profiles and
      `banking.events.transport`.

---

## 6. Verification

The whole suite, including the in-memory bus, the format and the configuration
tests:

```bash
mvn -B test
```

Most of this story's tests need neither Docker nor a broker:

| Test class                                      | What it covers                                                                     |
| ----------------------------------------------- | ---------------------------------------------------------------------------------- |
| `event/BusinessEventTest`                        | the format: the six types and their wire names, every envelope field, the at-least-one-identifier rule, blank/non-positive rejections, an unknown JSON field being ignored, and version handling                               |
| `event/InMemoryEventBusTest`                     | in-memory publish and subscribe: delivery to every handler of the type in subscription order, no delivery to handlers of another type, synchronous delivery, plus the local/test-only guard |
| `event/BrokerTypesNotReferencedInBusinessCodeTest` | that no controller or service names a Kafka or RabbitMQ class, and that `BusinessEventBus` carries no broker type in any signature |
| `event/kafka/KafkaEventBusTest`                  | the adapter against a mocked template: topic, `customer:`/`account:` key, dispatch by type, and a failing handler being logged while the other handlers still run |
| `config/EventBusConfigurationTest`               | configuration selection: Kafka by default and by name, in-memory under the `local` and `test` profiles, and the startup failure outside them – all with a mocked `KafkaTemplate` |

One test needs a real broker:

```bash
mvn -B test -Dtest=KafkaEventBusContainerTest
```

`KafkaEventBusContainerTest` starts a Kafka broker in a Testcontainers container,
publishes an event through `BusinessEventBus.publish` and asserts that the handler
registered through `subscribe(...)` receives it with the same `eventId`. It needs
**Docker** and nothing else – no external broker, no credentials, no network beyond
the container. When Docker is unavailable the test is skipped and the rest of the
suite still runs.

---

## Sign-off

| # | Check                                                                          | Result |
| - | ------------------------------------------------------------------------------ | ------ |
| 1 | One envelope covers all six types, each with a sample payload (section 2)        | ☐      |
| 2 | Every event carries id, type, version, time, customer/account, actor and correlation id (section 1) | ☐ |
| 3 | Business code uses `BusinessEventBus` only; no broker class in a controller or service | ☐ |
| 4 | `BANKING_EVENTS_TRANSPORT=IN_MEMORY` with the `local` profile runs with no broker | ☐ |
| 5 | Transport is chosen by configuration only, `KAFKA` by default                   | ☐      |
| 6 | Added fields are ignored by consumers; a breaking change increments `version`    | ☐      |
| 7 | `mvn -B test` passes, with Docker for the container test                        | ☐      |
| 8 | The in-memory transport is refused outside the `local` and `test` profiles      | ☐      |

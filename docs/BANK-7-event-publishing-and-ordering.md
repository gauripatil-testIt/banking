# BANK-7 – Publish business events in order: topic, key and outbox guide

Every business change BANK-6 defines an envelope for is now published to Kafka, in the
order it happened, without ever making a customer wait for the broker. This guide is
the **convention of record**: which topic an event goes to, which key it is published
with, how the outbox guarantees delivery while Kafka is down, how a failure is reported
and kept, and the commands that start a local broker and show real events in it.

It follows `docs/BANK-6-business-event-format.md`, which stays the format of record for
the envelope itself (fields, the six event types, versioning). Nothing in that guide is
changed here; BANK-7 only adds *how* an envelope reaches the broker.

---

## 1. What changed

BANK-6 delivered the format, the `BusinessEventBus` interface, the in-memory bus and the
Kafka adapter, but no business flow published anything. BANK-7 adds a **transactional
outbox** between the business services and that adapter:

```
CustomerService.create      ─┐
AccountService.create/update ├─ same transaction ─→ business_event_outbox (PENDING)
DocumentService.upload       │                              │
ExportService.write         ─┘                              │ scheduled drain
                                                            ▼
                                            BusinessEventBus.publish(...) ─→ Kafka
                                                            │  broker confirmed?
                                                            ├─ yes → PUBLISHED
                                                            └─ no  → retry, then FAILED + DLT
```

- The business action writes its data **and** its event row in **one transaction**. Kafka
  is not contacted on the request thread, so a customer action succeeds while the broker
  is unreachable (criterion 5).
- A scheduled publisher drains due rows, one at a time, in ascending id order, and marks a
  row `PUBLISHED` only **after** the broker acknowledged it (criterion 3).
- `publish` is no longer fire-and-forget: it waits for the send result and throws
  `EventPublishException`, carrying the event id and the reason, when Kafka did not
  acknowledge the record.
- An event that exhausts its attempts is kept as `FAILED` and reported on the dead-letter
  topic with its id and reason (criterion 7).

Which flows publish what:

| Business action                                       | Event type               | Key                        |
| ----------------------------------------------------- | ------------------------ | -------------------------- |
| `CustomerService.create`                              | `CUSTOMER_REGISTERED`    | `customer:{customerId}`    |
| `AccountService.create`                               | `ACCOUNT_OPENED`         | `account:{accountId}`      |
| `AccountService.update`, only when the status changed | `ACCOUNT_STATUS_CHANGED` | `account:{accountId}`      |
| `DocumentService.upload`, once the document is stored | `DOCUMENT_UPLOADED`      | `customer:{customerId}`    |
| `ExportService.write`, after the audit row            | `EXPORT_REQUESTED`       | `customer:{customerId}`    |

`STATEMENT_GENERATED` has no producing flow in this repository, so BANK-7 wires nothing
for it. The envelope and the constant stay exactly as BANK-6 defined them.

---

## 2. Topic convention: one topic for every event

| Topic                          | Purpose                                            | Partitions |
| ------------------------------ | -------------------------------------------------- | ---------- |
| `banking.business-events`      | every business event, whatever its type            | 3          |
| `banking.business-events.DLT`  | events that could not be published, with a reason  | 3          |

Both are derived from the one setting, `banking.events.topic`: the dead-letter topic is
`banking.events.topic` plus `.DLT`, so changing the topic setting moves both and the
convention never needs a second setting. The application declares both topics at startup
(`KafkaEventConfig`), so a freshly started local broker has them before the first event or
the first failure.

Consumers do not need a topic per event type: `type` is in the payload, and a consumer
subscribes to the one topic and filters on the envelope it already reads.

---

## 3. Key convention: account first, customer only when there is no account

| Event names                                   | Key                       | Example        |
| --------------------------------------------- | ------------------------- | -------------- |
| an account (with or without a customer)       | `account:{accountId}`     | `account:12`   |
| only a customer                               | `customer:{customerId}`   | `customer:1`   |

The rule is one sentence: **the account id whenever the event names an account, the
customer id only for a customer-only event.** A `STATEMENT_GENERATED`-style event naming
both is therefore keyed `account:…`: the ordering guarantee is per account, so the
account decides the partition.

With three partitions and a keyed producer:

- every event with the same key lands on the same partition, and a partition is consumed
  in append order – so **one account's events arrive in the order they happened**
  (criterion 2);
- different keys spread over the different partitions, so **one account's backlog never
  blocks another's**;
- the drain additionally refuses to publish a row while an earlier unresolved row with
  the same business key exists, so ordering holds even when a publish fails and is
  retried later.

The key a row is published with is computed once, when the event is recorded, and stored
in `business_key`; the drain and the dead-letter publisher both use it verbatim.

---

## 4. The outbox row and its lifecycle

Table `business_event_outbox` (Oracle DDL in
`src/main/resources/db/migration/V2__business_event_outbox.sql`; Hibernate generates the
equivalent H2 schema for the running application).

| Column             | Meaning                                                            |
| ------------------ | ------------------------------------------------------------------ |
| `event_id`         | the envelope's `eventId`; **UNIQUE**, so a retry cannot store a second copy |
| `event_type`       | the `EventType` constant name                                       |
| `event_version`    | the envelope version, `1` today                                     |
| `occurred_at`      | when the business change happened                                   |
| `customer_id` / `account_id` | what the event concerns (at least one is set)             |
| `actor` / `correlation_id` | `X-Actor` / `X-Correlation-Id` of the request, else `system` and a fresh UUID |
| `business_key`     | `account:{id}` or `customer:{id}` as in section 3                   |
| `payload`          | the serialised envelope, republished exactly as recorded            |
| `status`           | `PENDING`, `PUBLISHED` or `FAILED` – exactly those three            |
| `attempts`         | how many publish attempts have failed                               |
| `next_attempt_at`  | when the row may next be attempted                                  |
| `last_error`       | the reason of the last failure                                      |
| `published_at`     | when the broker acknowledged it                                     |
| `dead_lettered_at` | when the event was reported as dead-lettered                        |

State machine:

| From      | To          | When                                                                 |
| --------- | ----------- | -------------------------------------------------------------------- |
| –         | `PENDING`   | the business transaction records the event, `next_attempt_at = now`   |
| `PENDING` | `PUBLISHED` | the broker acknowledged the record; `published_at` is set             |
| `PENDING` | `PENDING`   | a publish failed: `attempts + 1`, `last_error`, `next_attempt_at = now + retry-delay` |
| `PENDING` | `FAILED`    | `attempts` reached `max-attempts`: `dead_lettered_at` set, event dead-lettered |

A `FAILED` row is **never deleted**: it is the copy kept for investigation. `PUBLISHED`
and `FAILED` rows are never selected by the drain again, so the same `eventId` cannot be
published twice by a retry.

### Duplicate suppression

Two mechanisms, both on the producing side (criterion 4):

1. **Producer idempotence with `acks=all`** – a publish counts as done only when every
   in-sync replica stored the record, and the producer's own retries cannot append the
   same record twice.
2. **The unique `event_id` on the stored event** – a business retry cannot even insert a
   second row for the same event, and a row that is already `PUBLISHED` is never sent
   again.

Consumer-side idempotency (acting on a duplicate the broker may deliver at-least-once) is
BANK-9, as `docs/BANK-6-business-event-format.md` says.

### Failure reporting

When a row reaches `max-attempts`, the event is published to
`banking.business-events.DLT` with the same `eventId` and the same key as the original,
**and** one structured `ERROR` log line is written carrying `eventId`, `type`,
`businessKey`, the topic and the failure reason. The row stays in the outbox as `FAILED`.
Under the in-memory transport there is no broker, so the report is the log line alone
(`LoggingDeadLetterPublisher`) and the application still starts.

Neither report contains actor email addresses or any other personal data; the actor is
the operator or system name the envelope already carries.

---

## 5. Settings

Nothing below has to be set for the defaults to work; the values are shown so a
deployment can override them.

| Property                                 | Environment variable               | Default                    | Meaning                                              |
| ---------------------------------------- | ---------------------------------- | -------------------------- | ---------------------------------------------------- |
| `banking.events.transport`               | `BANKING_EVENTS_TRANSPORT`         | `KAFKA`                    | `KAFKA` or `IN_MEMORY` (local/test only)              |
| `banking.events.topic`                   | `BANKING_EVENTS_TOPIC`             | `banking.business-events`  | the one topic; the dead-letter topic is this + `.DLT` |
| `banking.events.consumer-group`          | `BANKING_EVENTS_CONSUMER_GROUP`    | `banking-event-subscribers`| the group this application's subscribers share        |
| `banking.events.outbox.max-attempts`     | `BANKING_EVENTS_OUTBOX_MAX_ATTEMPTS`   | `5`    | failed publishes before the event is dead-lettered   |
| `banking.events.outbox.retry-delay`      | `BANKING_EVENTS_OUTBOX_RETRY_DELAY`    | `PT30S`| wait between two attempts of the same event          |
| `banking.events.outbox.batch-size`       | `BANKING_EVENTS_OUTBOX_BATCH_SIZE`     | `200`  | rows one drain takes                                 |
| `banking.events.outbox.drain-interval`   | `BANKING_EVENTS_OUTBOX_DRAIN_INTERVAL` | `PT1S` | how often the drain runs                             |

Producer settings that make "published" mean "stored", fixed in `KafkaEventConfig`:
`acks=all`, `enable.idempotence=true`, `request.timeout.ms=5000`, `delivery.timeout.ms=15000`.
`publish` waits for the acknowledgement on top of that (30 s), so a dead broker fails the
attempt quickly instead of holding the drain, and the row stays `PENDING` for the next one.

---

## 6. Start a local Kafka with one command

```bash
docker compose -f docker-compose.kafka.yml up -d
```

That is the whole local setup: one `confluentinc/cp-kafka:7.5.0` broker in KRaft mode on
`localhost:9092` (the same image the Testcontainers tests use).

Then start the application. The default transport is Kafka and the default bootstrap
server is `localhost:9092`, so no eventing setting is needed – only the document storage
one, which is unrelated to eventing:

```bash
BANKING_DOCUMENTS_STORAGE=local \
mvn spring-boot:run
```

Stop the broker again with:

```bash
docker compose -f docker-compose.kafka.yml down
```

`docker compose ... down -v` also throws away the broker's log, so the next start begins
with an empty topic – useful when a reviewer wants to read a run from the first event.

---

## 7. See the real events in the real topic

Register a customer and open an account, naming who did it and which request it belongs to:

```bash
curl -i -X POST http://localhost:8080/customers \
  -H 'Content-Type: application/json' \
  -H 'X-Actor: ops.user' \
  -H 'X-Correlation-Id: corr-bank-7-customer' \
  -d '{"firstName":"John","lastName":"Doe","email":"john@example.com","phone":"1234567890","status":"ACTIVE"}'

curl -i -X POST http://localhost:8080/accounts \
  -H 'Content-Type: application/json' \
  -H 'X-Actor: ops.user' \
  -H 'X-Correlation-Id: corr-bank-7-account' \
  -d '{"customerId":1,"accountNumber":"ACC001","type":"SAVINGS","balance":1000,"status":"ACTIVE"}'
```

Read the topic with the key shown in front of each event, newest last:

```bash
docker exec -it banking-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic banking.business-events \
  --from-beginning \
  --property print.key=true \
  --property key.separator="  ->  "
```

- [ ] The first line shows key `customer:1` and a `CUSTOMER_REGISTERED` envelope.
- [ ] The second line shows key `account:1` and an `ACCOUNT_OPENED` envelope carrying
      both `customerId` and `accountId`.
- [ ] Each envelope carries `eventId`, `type`, `version`, `occurredAt`, `actor` and
      `correlationId`, and the actor/correlation id are the ones the request sent.
- [ ] Updating the account's status (`PUT /accounts/1`) appends an
      `ACCOUNT_STATUS_CHANGED` event with key `account:1` **after** the `ACCOUNT_OPENED`
      one – same key, same partition, so the order is visible.
- [ ] Updating the account without changing its status appends nothing.

The rows behind those events:

```bash
curl -s http://localhost:8080/h2-console   # or query business_event_outbox from your SQL tool
```

Selecting `select event_id, event_type, business_key, status, attempts, published_at from
business_event_outbox order by id` should show both rows `PUBLISHED` with `published_at`
set – the drain marked them only after the broker acknowledged them.

### Kafka down, and back again (criterion 5)

```bash
docker compose -f docker-compose.kafka.yml stop kafka

curl -i -X POST http://localhost:8080/customers \
  -H 'Content-Type: application/json' \
  -d '{"firstName":"Jane","lastName":"Doe","email":"jane@example.com","phone":"123","status":"ACTIVE"}'

docker compose -f docker-compose.kafka.yml start kafka
```

- [ ] The `POST` still answers `201 Created` while the broker is stopped.
- [ ] The new row in `business_event_outbox` is `PENDING` with `attempts` growing and
      `last_error` naming the connection failure.
- [ ] After the broker is back, the row becomes `PUBLISHED` and the event appears on
      `banking.business-events` – with no operator action beyond restarting Kafka.

### A failure that is kept for investigation (criterion 7)

Stop the broker, set `BANKING_EVENTS_OUTBOX_MAX_ATTEMPTS=1` and
`BANKING_EVENTS_OUTBOX_RETRY_DELAY=PT1S`, perform one customer action, and read:

```bash
docker exec -it banking-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic banking.business-events.DLT \
  --from-beginning \
  --property print.key=true
```

- [ ] The application log has one `ERROR` line carrying the event id, its type and the
      failure reason.
- [ ] The row is kept as `FAILED` with `last_error` and `dead_lettered_at` set – it is
      not deleted.
- [ ] Once the broker is back, the event also appears on `banking.business-events.DLT`
      with the same id and key.

---

## 8. Verification

The whole suite, with no Docker and no broker: the outbox tests run against the in-memory
H2 database and a test double of `BusinessEventBus`, and the Testcontainers tests are
skipped when Docker is unavailable.

```bash
mvn -B test
```

Just the outbox, when iterating on it:

```bash
mvn -B test -Dtest='BusinessEventOutboxRecorderTest,BusinessEventOutboxPublisherTest'
```

The two tests that need a real broker (Docker):

```bash
mvn -B test -Dtest='KafkaEventBusContainerTest,OutboxEndToEndKafkaContainerTest'
```

| Test class                                        | What it covers                                                                                       |
| ------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `event/outbox/BusinessEventOutboxRecorderTest`     | one `PENDING` row per business action with the expected type and ids, both key forms, the actor and correlation id from the request, the unique `eventId` rejecting a second copy, and no row left behind when the transaction rolls back |
| `event/outbox/BusinessEventOutboxPublisherTest`    | per-key publishing in id order, a row waiting for an earlier unresolved row of its key while another key is still published, a failed publish staying `PENDING` with `attempts`/`last_error`/`next_attempt_at` and succeeding on a later drain, no second publish of an already `PUBLISHED` row, and `FAILED` + dead-letter + one `ERROR` line when attempts are exhausted |
| `event/outbox/OutboxEndToEndKafkaContainerTest`    | against a real broker in a Testcontainers container: envelope and key on the topic, per-account order, success while the broker is stopped, and the row becoming `PUBLISHED` after it returns |
| `event/kafka/KafkaEventBusTest`                    | the adapter against a mocked template: topic, `account:`/`customer:` key, publish waiting for the acknowledgement and failing with the event id, dispatch by type |
| `event/kafka/KafkaEventBusContainerTest`           | the adapter against a real broker: publish and subscribe round trip with the same event id                                      |
| `event/BusinessEventTest`                          | the envelope itself, unchanged from BANK-6                                                          |
| `event/InMemoryEventBusTest`, `config/EventBusConfigurationTest` | the in-memory bus and transport selection, unchanged from BANK-6                     |
| `event/BrokerTypesNotReferencedInBusinessCodeTest` | business code still names no broker class, and `BusinessEventBus` still declares `publish` and `subscribe` only |

---

## 9. Notes and limitations

- **Nothing is published from the request thread.** A business action returns as soon as
  its transaction commits; how long the event takes to reach the topic is the drain's
  business, one second at a time by default.
- **A publish is retried, not re-recorded.** The drain republishes the stored `payload`
  byte for byte, so the event keeps its `eventId` and `occurredAt` across attempts.
- **Ordering is per key, not global.** Events of one account are ordered; events of two
  accounts have no defined order relative to each other, which is exactly what
  "different accounts do not block each other" requires.
- **Consumer-side retry, dead-lettering of failing handlers and Micrometer metrics are
  BANK-9**, as `docs/BANK-6-business-event-format.md` says. BANK-7's dead-letter topic is
  for an event that cannot be *published*, not for one that cannot be *handled*.
- **No HTTP endpoint inspects or replays the outbox.** A `FAILED` row is read from the
  table and the dead-letter topic.
- The in-memory bus is unchanged and remains local/test only; the default transport is
  still Kafka.

---

## Sign-off

| # | Check                                                                                  | Result |
| - | -------------------------------------------------------------------------------------- | ------ |
| 1 | Every BANK-6 event that has a producing flow is recorded in the outbox by the business transaction | ☐ |
| 2 | Events of one account arrive in the order they happened; different accounts do not block each other | ☐ |
| 3 | An event counts as published only once Kafka acknowledged it, and failures are retried automatically | ☐ |
| 4 | Retries create no duplicate: `acks=all` + idempotence, and a unique `eventId` on the stored event | ☐ |
| 5 | Customer actions succeed while Kafka is down and the events arrive when it returns         | ☐ |
| 6 | One topic `banking.business-events`, keyed `account:{id}` or `customer:{id}` as in section 3 | ☐ |
| 7 | An event that cannot be published is reported with its id and reason, and kept as `FAILED` and on the DLT | ☐ |
| 8 | `mvn -B test` covers publish, ordering per key, retry, duplicate suppression and recovery after an outage | ☐ |
| 9 | `docker compose -f docker-compose.kafka.yml up -d` starts the local Kafka                | ☐ |
| 10 | A reviewer sees real events, with their keys, in the real topic (section 7)               | ☐ |

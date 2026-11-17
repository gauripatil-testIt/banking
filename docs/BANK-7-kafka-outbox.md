# BANK-7 – Publishing business events to Kafka: the outbox, the relay and one-command local Kafka

BANK-6 defined the event format, the six event types and a fire-and-forget Kafka adapter, and wired
nothing to it. BANK-7 makes publishing **durable and ordered**:

* every business event is written to an **outbox row in the same database transaction as the business
  change** it announces, so a broker outage can never fail or lose a customer action;
* a **scheduled relay** drains those rows to Kafka with the real producer configuration
  (`acks=all`, `enable.idempotence=true`), one message key per subject, and deletes a row only once
  the broker has acknowledged it;
* a **transient** send failure is retried forever with exponential backoff; a **non-retryable** one is
  kept in the database as a `FAILED` row carrying the event id and the reason.

This page documents what is published, the one topic and ordering-key convention, the row lifecycle,
the configuration, the one command that starts a local broker, and how to watch the whole thing work.

---

## 1. What is published

Five of BANK-6's six event types have a producing business action, and each is recorded by the
action's service while that action's transaction is open:

| Event type (`type` on the wire) | Version | Producing business action                                        |
| ------------------------------- | ------- | ---------------------------------------------------------------- |
| `customer.registered`           | 1       | `POST /customers` – `CustomerService.create`                     |
| `account.opened`                | 1       | `POST /accounts` – `AccountService.create`                       |
| `account.status.changed`        | 1       | `PUT /accounts/{id}` – `AccountService.update`, when the status actually changes |
| `document.uploaded`             | 1       | `POST /customers/{customerId}/documents` – `DocumentService.upload` |
| `export.requested`              | 1       | `GET /exports` – `ExportService.write`, next to the export audit row |

**`statement.generated` is not published.** BANK-6 defined the type and its payload only, because
this application has no statement entity, table or endpoint; a statement generation feature is a
separate story. Criterion 1 of this ticket is therefore met for the five event types that have a
business action, and the sixth stays a defined-but-unused wire value until that feature exists.

No service or controller refers to a Kafka class: the only way business code announces something is
`BusinessEventOutbox.record(...)`, which writes a row and nothing else.

---

## 2. The convention: one topic, one ordering key

| What           | Value                                  | Where it comes from                          |
| -------------- | -------------------------------------- | -------------------------------------------- |
| Topic          | `banking.events` for **all** event types | `banking.events.topic` (`EventBusProperties`), copied into `event_outbox.topic` when the row is written |
| Ordering key   | `<subjectType>-<subjectId>`, subject type upper-cased: `CUSTOMER-1`, `ACCOUNT-42` | Computed once by the outbox writer, stored in `outboxevent.ordering_key` |
| Message key    | the same ordering key                  | The relay sends each row with its stored `ordering_key` as the key |

There is exactly **one** topic. Event types are told apart by the `type` field inside the envelope
(BANK-6), never by a per-type topic, so a consumer subscribes to one topic and filters by `type`.

The ordering key is the business subject of the event:

* `CUSTOMER-1` – every event about customer 1;
* `ACCOUNT-42` – every event about account 42, so the two changes of one account's status arrive in
  the order they happened.

Kafka guarantees order within a partition, and identical keys hash to the same partition, so all
events for `ACCOUNT-42` land on one partition in send order while a different account hashes to its
own partition and is not held up by it. The exporter has no single subject, so its
`export.requested` events are recorded against the bank-wide subject `CUSTOMER-0` and stay ordered
among themselves.

Two guards make the per-subject order hold even under failure:

1. `outboxevent.sequence_no` is allocated per ordering key and `(subject_type, subject_id,
   sequence_no)` is unique, so the order is in the data, not in timing.
2. A relay pass sends only the **oldest pending row of each ordering key** (rows are read ordered by
   `ordering_key, sequence_no` and the first row per key is taken), so a later change can never
   overtake an earlier one that is still waiting for a retry.

---

## 3. The outbox row: what happens to an event

The table is created by `src/main/resources/db/migration/V2__create_event_outbox.sql` (Oracle DDL;
the H2 schema used by the tests is generated from `OutboxEvent`).

| Column                                                   | Meaning                                                             |
| -------------------------------------------------------- | ------------------------------------------------------------------- |
| `event_id`                                               | The envelope's `eventId`, unique: what a consumer deduplicates on.   |
| `event_type`, `event_version`, `subject_type`, `subject_id` | The envelope's routing fields, as separate columns.               |
| `sequence_no`, `ordering_key`                            | The per-subject order and the Kafka message key.                     |
| `topic`                                                  | The configured topic, frozen into the row when it was written.        |
| `payload` (CLOB)                                         | The exact JSON `BusinessEventJson.toJson(...)` wrote, sent verbatim.  |
| `status`                                                 | `PENDING` or `FAILED`.                                               |
| `attempt_count`, `failure_reason`                        | How often it was tried and why it last failed.                       |
| `created_at`, `next_attempt_at`, `published_at`           | When it was recorded, when it may be tried next, when it was published. |

Lifecycle:

1. **Written** – inside the caller's transaction (`Propagation.MANDATORY`, so a call outside a
   transaction fails loudly instead of writing an event outside it). The row commits or rolls back
   with the business change, and it only becomes visible to the relay **after** that commit, so
   nothing is ever published for a change that did not happen.
2. **PENDING** – the relay polls (`banking.events.outbox.poll-interval-ms`, default 5 s), sends the
   row's stored JSON to the row's topic with the row's ordering key, and waits for the broker to
   acknowledge it.
3. **Acknowledged → deleted** – a row counts as published only when the send completed successfully;
   it is then removed in a short transaction.
4. **Transient failure → still PENDING** – `attempt_count` is incremented, `failure_reason` is
   recorded and `next_attempt_at` moves out by `min(base-delay * 2^attempt_count, max-delay)`
   (defaults: 5 s base, 5 min cap). The relay **never gives up** on a transient failure: no attempt
   limit, no dead-letter status. A broker that is down for an hour just means the events wait.
5. **Non-retryable failure → FAILED** – a failure that can never succeed on a retry (for example an
   invalid topic name or a record the producer cannot serialize) marks the row `FAILED`. The row
   **stays in the table**, is never selected for sending again, and is the investigation record
   criterion 7 asks for: event id plus reason.

Finding the events that need a human:

```sql
SELECT id, event_id, event_type, ordering_key, attempt_count, failure_reason, created_at
  FROM outboxevent
 WHERE status = 'FAILED'
 ORDER BY id;
```

What is still waiting for the broker (a rising count during an outage is expected, and it drains by
itself when the broker returns):

```sql
SELECT ordering_key, COUNT(*), MIN(created_at), MAX(attempt_count)
  FROM outboxevent
 WHERE status = 'PENDING'
 GROUP BY ordering_key
 ORDER BY ordering_key;
```

---

## 4. Why retries do not create actionable duplicates

* The producer is configured with `acks=all` and `enable.idempotence=true`
  (`KafkaProducerConfig`), so a producer-session retry cannot store the same record twice.
* The payload is written to the row **once** and sent verbatim, so every attempt at a row carries
  the **same `eventId`**. The only duplicate the system can produce is a record the broker stored
  before its acknowledgement was lost, and that duplicate is the same logical event.
* Downstream systems deduplicate on `eventId`, which is exactly what BANK-6's envelope documents.
* A row is deleted only after the acknowledgement, so nothing is "published" that the broker does
  not have.

---

## 5. Configuration

| Property                                    | Environment variable                          | Default    | Meaning                                              |
| ------------------------------------------- | --------------------------------------------- | ---------- | ---------------------------------------------------- |
| `banking.events.bus`                        | `BANKING_EVENTS_BUS`                          | `kafka`    | `kafka` or `in-memory` (`EventBusType`).             |
| `banking.events.topic`                      | `BANKING_EVENTS_TOPIC`                        | `banking.events` | The one topic every event is published to.      |
| `banking.events.kafka.bootstrap-servers`    | `BANKING_EVENTS_KAFKA_BOOTSTRAP_SERVERS`      | none – required | Broker address; startup fails with a readable message when it is unset while the bus is Kafka. |
| `banking.events.kafka.client-id`            | `BANKING_EVENTS_KAFKA_CLIENT_ID`              | `banking-service` | Producer `client.id`.                          |
| `banking.events.outbox.poll-interval-ms`    | `BANKING_EVENTS_OUTBOX_POLL_INTERVAL_MS`      | `5000`     | How often the relay looks for due rows.              |
| `banking.events.outbox.base-delay-ms`       | `BANKING_EVENTS_OUTBOX_BASE_DELAY_MS`         | `5000`     | First retry delay; doubles per attempt.              |
| `banking.events.outbox.max-delay-ms`        | `BANKING_EVENTS_OUTBOX_MAX_DELAY_MS`          | `300000`   | Ceiling of the exponential backoff.                  |
| `banking.events.outbox.send-timeout-seconds`| `BANKING_EVENTS_OUTBOX_SEND_TIMEOUT_SECONDS`  | `10`       | How long the relay waits for the broker's acknowledgement before treating the attempt as failed. |

`src/main/resources/application.properties` keeps the in-memory bus for the default profile;
`src/main/resources/application-local.properties` switches the publisher to the local broker below.

---

## 6. One command: start the local broker

```bash
docker compose up -d
```

That starts a single-node Kafka in KRaft mode (no ZooKeeper) from `docker-compose.yml`, reachable at
`localhost:9092`. Confirm it is running and stop it again with:

```bash
docker compose ps
docker compose logs -f kafka        # optional
docker compose stop kafka           # the outage check in section 7
docker compose start kafka
docker compose down                 # remove it when finished
```

Run the application against it with the `local` profile:

```bash
BANKING_DOCUMENTS_STORAGE=local mvn spring-boot:run -Dspring-boot.run.profiles=local
```

The profile supplies `banking.events.bus=kafka` and
`banking.events.kafka.bootstrap-servers=localhost:9092`; the document storage variable is only needed
because the default document backend is S3 (see `docs/BANK-5-s3-document-storage-deployment-guide.md`).

Watch the topic, with keys (the ordering key is the first column):

```bash
docker compose exec kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic banking.events \
  --from-beginning \
  --property print.key=true
```

---

## 7. Verify it end to end

**AC1, AC2, AC6 – events are published, ordered per key, on the documented topic and key.**

```bash
# 1. a customer: one customer.registered event, key CUSTOMER-<id>
curl -i -X POST http://localhost:8080/customers \
  -H 'Content-Type: application/json' \
  -d '{"firstName":"John","lastName":"Doe","email":"john@example.com","phone":"1234567890","status":"ACTIVE"}'

# 2. an account for that customer: one account.opened event, key ACCOUNT-<id>
curl -i -X POST http://localhost:8080/accounts \
  -H 'Content-Type: application/json' \
  -d '{"customerId":1,"accountNumber":"00012345","type":"SAVINGS","balance":1000.00,"status":"ACTIVE"}'

# 3. two status changes on the same account: two account.status.changed events, key ACCOUNT-1,
#    in this order (ACTIVE -> INACTIVE, then INACTIVE -> CLOSED)
for status in INACTIVE CLOSED; do
  curl -s -X PUT http://localhost:8080/accounts/1 \
    -H 'Content-Type: application/json' \
    -d "{\"customerId\":1,\"accountNumber\":\"00012345\",\"type\":\"SAVINGS\",\"balance\":1000.00,\"status\":\"$status\"}" > /dev/null
done
```

The console consumer of section 6 then shows, at most one poll interval later:

- every record under the single topic `banking.events`;
- keys `CUSTOMER-1` and `ACCOUNT-1` (never a UUID, never a file name);
- the two `account.status.changed` records adjacent and in the order they happened, with
  `"previousStatus":"ACTIVE"`/`"newStatus":"INACTIVE"` before
  `"previousStatus":"INACTIVE"`/`"newStatus":"CLOSED"`.

**AC5 – an outage loses nothing, and customer actions keep working.**

```bash
docker compose stop kafka

# the action still succeeds (201), even though nothing can be published
curl -i -X POST http://localhost:8080/customers \
  -H 'Content-Type: application/json' \
  -d '{"firstName":"Jane","lastName":"Doe","email":"jane@example.com","phone":"1234567890","status":"ACTIVE"}'

# the event is waiting in the outbox, counted, still PENDING
#   SELECT ordering_key, status, attempt_count, next_attempt_at FROM event_outbox ORDER BY id;

docker compose start kafka
```

Within one poll interval after the broker returns the `customer.registered` event for Jane appears
in `banking.events` and its row disappears from `outboxevent`.

**AC3, AC4, AC7 – retries, no actionable duplicates, an unpublishable event kept for investigation.**

- Stop the broker for a minute: `attempt_count` on the waiting rows rises and `next_attempt_at`
  moves further out each time (exponential backoff). Restart it: the rows drain in per-key sequence
  order and are deleted.
- Every record on the wire carries `eventId`; two records with the same `eventId` are the same
  logical event for a deduplicating consumer.
- A row that can never be published (for example a topic name made invalid by configuration) is left
  as `status = 'FAILED'` with its `event_id` and `failure_reason` in Oracle, and is not retried: see
  the two queries in section 3.

---

## 8. Automated tests

| Test                                   | Needs Docker | Covers                                                                    |
| -------------------------------------- | ------------ | ------------------------------------------------------------------------- |
| `OutboxEventRepositoryTest`            | no           | The row: CLOB payload round trip, status/reason/attempt count, the pending query, per-key sequence numbers. |
| `OutboxRelayTest`                      | no           | The relay with a mocked `KafkaTemplate`: delete only on acknowledgement, transient failure + backoff, non-retryable `FAILED`, per-key ordering, no resend after an acknowledgement. |
| `OrderingKeyConventionTest`            | no           | `CUSTOMER-1` / `ACCOUNT-42` ordering keys and that the topic comes from configuration. |
| `OutboxRelayKafkaIT`                   | yes          | A real Kafka container and the real producer configuration: per-key order on the wire, two accounts not blocking each other, retry reusing one `eventId`. |
| `OutboxOutageRecoveryIT`               | yes          | A customer create with no broker (row stays `PENDING`), delivery when it returns, transient failure with backoff. |

The `*IT` classes are named so that `mvn -B test` (surefire) still passes on a machine without a
Docker daemon; they are skipped when Docker is unavailable. To run them:

```bash
mvn -B test -Dtest=OutboxRelayKafkaIT
mvn -B test -Dtest=OutboxOutageRecoveryIT
```

They use a Testcontainers Kafka container and nothing else: no broker to install, no AWS, no network
access beyond the local Docker daemon.

---

## 9. Not in this story

* **Consuming events** – no `@KafkaListener`, no idempotent consumer storage, no dead-letter topic.
  Publishing is the one direction this ticket asks for; consumption stays BANK-9.
* **A statement generation feature** – hence no `statement.generated` event (section 1).
* **Purging or archiving `FAILED` rows** – they are kept for investigation; retention is an operator
  decision.
* **A schema registry, Avro/Protobuf, or a second broker** – JSON with the BANK-6 envelope only.
* **Metrics and dashboards** – the relay logs publish and failure outcomes (event id, type, topic,
  ordering key; never a customer name or an email) with structured logging, and this repository has
  no metrics registry configured.

---

## Sign-off

| # | Check                                                                                   | Result |
| - | --------------------------------------------------------------------------------------- | ------ |
| 1 | The five event types with a business action are written to the outbox in the business transaction | ☐ |
| 2 | One topic `banking.events`, ordering key `<subjectType>-<subjectId>` (`ACCOUNT-42`), oldest row per key sent first | ☐ |
| 3 | A row is deleted only after the broker acknowledges it; transient failures retry forever with exponential backoff | ☐ |
| 4 | Retries reuse the stored payload and therefore the same `eventId`; producer is idempotent with `acks=all` | ☐ |
| 5 | With Kafka down the business action succeeds and the event is delivered when the broker returns | ☐ |
| 6 | Non-retryable failures leave a `FAILED` row with its event id and reason in Oracle | ☐ |
| 7 | `docker compose up -d` starts the local broker; the topic is readable with the documented command | ☐ |
| 8 | `statement.generated` is documented as having no producing business action yet              | ☐ |

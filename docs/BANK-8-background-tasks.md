# BANK-8 – Hand slow work to background workers: queues, routing and outbox guide

Slow work – a welcome email, a statement, a document export, a document scan – is no longer
part of the request that triggers it. Each is submitted as a **background task** to its own
RabbitMQ queue, in the same transaction that commits the business change, and a scheduled
drain actually publishes it. A request never waits for the broker, and a task submitted
while RabbitMQ is down is queued once it returns.

This guide is the convention of record: which queue and routing rule each task type owns,
what a task message contains, how the outbox guarantees delivery, the settings and their
defaults, and the commands that start a local broker and show real messages in the real
queues.

**BANK-8 stops at submission.** No consumer performs any of the four tasks in this change;
the message sitting in its queue is the deliverable, and whoever builds the workers picks
the messages up from there. The Kafka business events of
`docs/BANK-7-event-publishing-and-ordering.md` are untouched: the same business actions
still record `CUSTOMER_REGISTERED`, `DOCUMENT_UPLOADED` and `EXPORT_REQUESTED` in
`business_event_outbox`, and BANK-8 only adds the task row next to it.

---

## 1. What changed

```
CustomerService.create       ─┐
DocumentService.upload        ├─ same transaction ─→ background_task_outbox (PENDING)
ExportService.write           │                              │
AccountService.requestStatement ─┘                          │ scheduled drain
                                                             ▼
                                       BackgroundTaskPublisher.publish(...) ─→ RabbitMQ
                                                             │  broker confirmed?
                                                             ├─ yes → PUBLISHED
                                                             └─ no  → retry, then FAILED
```

- The business action writes its data **and** its task row in **one transaction**. RabbitMQ
  is not contacted on the request thread, so a customer action succeeds while the broker is
  unreachable (criterion 7).
- A scheduled publisher drains due rows in ascending id order and marks a row `PUBLISHED`
  only **after** the broker confirmed receipt (criterion 3).
- `publish` is not fire-and-forget: it waits for the publisher confirm and throws
  `TaskPublishException`, carrying the task id and the reason, when RabbitMQ did not
  confirm the message. A transient failure is retried by the next drain.
- An event that exhausts its attempts is kept as `FAILED` with its reason and reported in
  one structured `ERROR` line – never deleted.

Which business action submits which task:

| Business action                          | Task type                | `recordType` | `recordId`                              | `customerId`     |
| ---------------------------------------- | ------------------------ | ------------ | --------------------------------------- | ---------------- |
| `POST /customers`                        | `WELCOME_EMAIL`          | `customer`   | the new customer's id                   | the new customer |
| `POST /customers/{id}/documents`         | `DOCUMENT_SCAN`          | `document`   | the stored document's id                | `{id}`           |
| `GET /exports`                           | `DOCUMENT_EXPORT`        | `data_export`| the customer the export is announced for| same customer    |
| `POST /accounts/{id}/statements`         | `STATEMENT_GENERATION`   | `account`    | `{id}`                                  | the account owner |

A rejected action submits nothing: a document upload refused for its type, size or customer
leaves no document row and no task row, and an export with an unknown type writes neither
its audit row nor a task.

`POST /accounts/{id}/statements` is the new endpoint behind statement generation – nothing
in the repository triggered it before – and it answers `202 Accepted`:

```json
{
  "taskId": "0e0b9c8d-1a2b-3c4d-8e9f-0a1b2c3d4e5f",
  "taskType": "STATEMENT_GENERATION",
  "accountId": 1
}
```

---

## 2. The four queues and their routing rules

One topic exchange, **one durable queue per task type, one routing key per task type**, so
one slow task cannot hold up another (criterion 5):

| Task type                | Queue                                        | Routing key                 |
| ------------------------ | -------------------------------------------- | --------------------------- |
| `WELCOME_EMAIL`          | `banking.background-tasks.welcome-email`      | `task.welcome-email`        |
| `STATEMENT_GENERATION`   | `banking.background-tasks.statement-generation` | `task.statement-generation` |
| `DOCUMENT_EXPORT`        | `banking.background-tasks.document-export`    | `task.document-export`      |
| `DOCUMENT_SCAN`          | `banking.background-tasks.document-scan`      | `task.document-scan`        |

The exchange is `banking.background-tasks` (`banking.tasks.exchange`). The queue name is the
exchange name plus the task type in kebab case; the routing key is `task.` plus the same
kebab-cased type. Both are declared in one place in code, `TaskQueue`, so the topology, the
publisher and this table cannot drift apart.

The application declares the exchange, the four queues and the four bindings at startup
(`BackgroundTaskRabbitConfig`), so a freshly started local broker has every queue before the
first message. The queues are durable and every message is sent persistent.

---

## 3. The message every task is submitted in

Every task, whatever its type, is one JSON object with these fields:

| Field           | JSON type             | Required | Meaning                                                        |
| --------------- | --------------------- | -------- | -------------------------------------------------------------- |
| `taskId`        | string (UUID)         | yes      | Stable identity of the task – derived from type and record, see below |
| `type`          | string (enum)         | yes      | `WELCOME_EMAIL`, `STATEMENT_GENERATION`, `DOCUMENT_EXPORT`, `DOCUMENT_SCAN` |
| `recordType`    | string                | yes      | what the task applies to: `customer`, `document`, `data_export`, `account` |
| `recordId`      | number (long)         | yes      | the id of that record                                          |
| `customerId`    | number (long)         | no       | the customer the work concerns, when there is one               |
| `requestedBy`   | string                | yes      | who asked for it – `X-Actor` of the request, else `system`      |
| `correlationId` | string                | yes      | which request it belongs to – `X-Correlation-Id`, else a fresh UUID |
| `occurredAt`    | string (ISO-8601 UTC) | yes      | when the task was requested, from the application's `Clock` bean |

A customer registration produces:

```json
{
  "taskId": "<uuid derived from WELCOME_EMAIL:customer:1>",
  "type": "WELCOME_EMAIL",
  "recordType": "customer",
  "recordId": 1,
  "customerId": 1,
  "requestedBy": "ops.user",
  "correlationId": "corr-bank-8-customer",
  "occurredAt": "2025-03-04T09:07:45Z"
}
```

A statement request produces the same shape with `"type": "STATEMENT_GENERATION"`,
`"recordType": "account"` and the account's id in `recordId`.

The `taskId` is **not** random: it is a name-based UUID over `type:recordType:recordId`.
The same task for the same record therefore always carries the same `taskId`, which is what
makes a duplicate submission recognisable – a worker can dedupe on it, and the outbox
already refuses to queue it twice.

---

## 4. The task outbox and its lifecycle

Table `background_task_outbox` (Oracle DDL in
`src/main/resources/db/migration/V3__background_task_outbox.sql`; Hibernate generates the
equivalent H2 schema for the running application).

| Column             | Meaning                                                              |
| ------------------ | -------------------------------------------------------------------- |
| `task_id`          | the message's `taskId`; **UNIQUE**, so a duplicate submission cannot store a second copy |
| `task_type`        | the `TaskType` constant name                                          |
| `record_type` / `record_id` | what the task applies to                                    |
| `customer_id`      | the customer the work concerns, when there is one                     |
| `requested_by` / `correlation_id` | `X-Actor` / `X-Correlation-Id` of the request, else `system` and a fresh UUID |
| `payload`          | the serialised message, published exactly as recorded                 |
| `status`           | `PENDING`, `PUBLISHED` or `FAILED` – exactly those three              |
| `attempts`         | how many publish attempts have failed                                 |
| `next_attempt_at`  | when the row may next be attempted                                    |
| `last_error`       | the reason of the last failure                                        |
| `published_at`     | when the broker confirmed receipt                                     |
| `created_at`       | when the business transaction recorded the task                        |

State machine:

| From      | To          | When                                                                  |
| --------- | ----------- | --------------------------------------------------------------------- |
| –         | `PENDING`   | the business transaction records the task, `next_attempt_at = now`     |
| `PENDING` | `PUBLISHED` | RabbitMQ confirmed the message; `published_at` is set                  |
| `PENDING` | `PENDING`   | a publish failed: `attempts + 1`, `last_error`, `next_attempt_at = now + retry-delay` |
| `PENDING` | `FAILED`    | `attempts` reached `max-attempts`: the row is kept and reported        |

A `FAILED` row is **never deleted**: it is the copy kept for investigation. `PUBLISHED` and
`FAILED` rows are never selected by the drain again, so a confirmed task cannot be published
twice.

### Duplicate suppression (criterion 6)

Two mechanisms, both on the producing side:

1. **The derived `taskId`** – the same task for the same record always maps to the same
   `taskId`, and the recorder returns the already-recorded message instead of inserting a
   second row.
2. **The unique `task_id` on the stored task** – the backstop when two requests race; the
   losing insert is treated as "already submitted" rather than as a failure.

### Failure reporting (criterion 3 and 7)

A nack, a message returned as unroutable, a confirmation that does not arrive within
`banking.tasks.publish-timeout`, or I/O failure all surface as `TaskPublishException` and
leave the row `PENDING` with its retry scheduled. When the attempts are exhausted the row is
kept as `FAILED` and one structured `ERROR` line carries the task id, the task type and the
reason. No message body or personal data is logged.

---

## 5. Settings

Nothing below has to be set for the defaults to work; the values are shown so a deployment
can override them.

| Property                          | Environment variable                | Default                     | Meaning                                            |
| --------------------------------- | ----------------------------------- | --------------------------- | -------------------------------------------------- |
| `banking.tasks.exchange`          | `BANKING_TASKS_EXCHANGE`            | `banking.background-tasks`  | the topic exchange every task is published to       |
| `banking.tasks.max-attempts`      | `BANKING_TASKS_MAX_ATTEMPTS`        | `5`                         | failed publishes before the task is kept as `FAILED` |
| `banking.tasks.retry-delay`       | `BANKING_TASKS_RETRY_DELAY`         | `PT30S`                     | wait between two attempts of the same task          |
| `banking.tasks.batch-size`        | `BANKING_TASKS_BATCH_SIZE`          | `200`                       | rows one drain takes                                |
| `banking.tasks.drain-interval`    | `BANKING_TASKS_DRAIN_INTERVAL`      | `PT1S`                      | how often the drain runs                            |
| `banking.tasks.publish-timeout`   | `BANKING_TASKS_PUBLISH_TIMEOUT`     | `PT30S`                     | how long a publish waits for the broker's confirm   |

Spring Boot's own broker settings, set in `application.properties`:

| Property                            | Default     | Meaning                                                         |
| ----------------------------------- | ----------- | --------------------------------------------------------------- |
| `spring.rabbitmq.host`              | `localhost` | the broker the application talks to                              |
| `spring.rabbitmq.port`              | `5672`      | AMQP port                                                        |
| `spring.rabbitmq.publisher-confirm-type` | `correlated` | every publish asks for a confirm – that is what "submitted" means |
| `spring.rabbitmq.publisher-returns` | `true`      | an unroutable message comes back instead of disappearing          |
| `spring.rabbitmq.template.mandatory`| `true`      | the return above is requested for every message                   |

A deployment supplies `SPRING_RABBITMQ_HOST`, `SPRING_RABBITMQ_PORT`,
`SPRING_RABBITMQ_USERNAME` and `SPRING_RABBITMQ_PASSWORD` (or the equivalent
`spring.rabbitmq.*` values) for its own broker; nothing broker-specific is committed beyond
the local defaults above.

---

## 6. Start a local RabbitMQ with one command

```bash
docker compose -f docker-compose.rabbitmq.yml up -d
```

That is the whole local setup (criterion 9): one `rabbitmq:3-management` container named
`banking-rabbitmq` on `localhost:5672`, with the management UI on
<http://localhost:15672> (`guest` / `guest`). It matches the defaults in
`application.properties`, so no task or broker setting is needed locally:

```bash
mvn spring-boot:run
```

Stop it again with:

```bash
docker compose -f docker-compose.rabbitmq.yml down
```

`docker compose ... down -v` also throws away the broker's state, so the next start begins
with empty queues – useful when a reviewer wants to read a run from the first task.

---

## 7. See the real messages in the real queues

Create a customer, upload a document, export, and request a statement, naming who did it and
which request it belongs to:

```bash
curl -i -X POST http://localhost:8080/customers \
  -H 'Content-Type: application/json' \
  -H 'X-Actor: ops.user' \
  -H 'X-Correlation-Id: corr-bank-8-customer' \
  -d '{"firstName":"John","lastName":"Doe","email":"john@example.com","phone":"1234567890","status":"ACTIVE"}'

curl -i -X POST http://localhost:8080/customers/1/documents \
  -H 'X-Actor: ops.user' \
  -H 'X-Correlation-Id: corr-bank-8-document' \
  -F "file=@docs/samples/sample-valid.pdf" \
  -F "documentType=ID_PROOF" \
  -F "uploadedBy=ops.user"

curl -i -o /tmp/banking-export.csv \
  -H 'X-Actor: ops.user' \
  -H 'X-Correlation-Id: corr-bank-8-export' \
  "http://localhost:8080/exports?type=customers&exportedBy=ops.user"

curl -i -X POST http://localhost:8080/accounts/1/statements \
  -H 'X-Actor: ops.user' \
  -H 'X-Correlation-Id: corr-bank-8-statement'
```

Then read the queues. The drain runs once a second, so give it a moment:

```bash
docker exec -it banking-rabbitmq rabbitmqctl list_queues name messages consumers
```

- [ ] `banking.background-tasks.welcome-email` holds one message.
- [ ] `banking.background-tasks.document-scan` holds one message.
- [ ] `banking.background-tasks.document-export` holds one message.
- [ ] `banking.background-tasks.statement-generation` holds one message.
- [ ] Each queue holds **only** the tasks of its own type (criterion 5) – a welcome-email
      message is never in `…document-scan`.
- [ ] `consumers` is `0` for every queue: this change submits, it does not consume.

The routing rules, as the broker itself sees them:

```bash
docker exec -it banking-rabbitmq rabbitmqctl list_bindings source_name destination_name routing_key
```

To read one real message body, open <http://localhost:15672> → *Queues and Streams* → the
queue → *Get messages*, or ask the management API for one message without consuming it
(`ack_requeue_true` puts it back):

```bash
curl -s -u guest:guest \
  "http://localhost:15672/api/queues/%2F/banking.background-tasks.welcome-email/get" \
  -H 'Content-Type: application/json' \
  -d '{"count":1,"ackmode":"ack_requeue_true","encoding":"auto"}'
```

- [ ] The body carries `taskId`, `type`, `recordType`, `recordId`, `requestedBy`,
      `correlationId` and `occurredAt` (criterion 2).
- [ ] `requestedBy` and `correlationId` are the `X-Actor` and `X-Correlation-Id` the request
      sent.

The rows behind those messages:

```sql
select task_id, task_type, record_type, record_id, status, attempts, published_at
from background_task_outbox order by id
```

Every row should be `PUBLISHED` with `published_at` set – the drain marked them only after
the broker confirmed them.

---

## 8. Restart and outage checks (criteria 4 and 7)

### A queued task survives a broker restart (criterion 4)

Submit a task, confirm it is in its queue, then restart the broker:

```bash
docker compose -f docker-compose.rabbitmq.yml restart rabbitmq

docker exec -it banking-rabbitmq rabbitmqctl list_queues name messages
```

- [ ] After the restart the message is still in its queue – the queues are durable and the
      messages are persistent, and nothing consumed it.
- [ ] No new `background_task_outbox` row was written and no row changed state because of the
      restart.

### RabbitMQ down, and back again (criterion 7)

```bash
docker compose -f docker-compose.rabbitmq.yml stop rabbitmq

curl -i -X POST http://localhost:8080/customers \
  -H 'Content-Type: application/json' \
  -H 'X-Actor: ops.user' \
  -d '{"firstName":"Jane","lastName":"Doe","email":"jane@example.com","phone":"123","status":"ACTIVE"}'

docker compose -f docker-compose.rabbitmq.yml start rabbitmq
```

- [ ] The `POST` still answers `201 Created` while the broker is stopped.
- [ ] The new row in `background_task_outbox` is `PENDING` with `attempts` growing and
      `last_error` naming the connection failure.
- [ ] After the broker is back, the row becomes `PUBLISHED` and the message appears in
      `banking.background-tasks.welcome-email` – with no operator action beyond restarting
      RabbitMQ.

A short demo outage is easier on the default five attempts thirty seconds apart than it looks;
to show the whole cycle quickly, restart the application with a patient retry policy:

```bash
BANKING_TASKS_MAX_ATTEMPTS=50 \
BANKING_TASKS_RETRY_DELAY=PT5S \
mvn spring-boot:run
```

### A task that could never be sent (criterion 3)

Stop the broker, set `BANKING_TASKS_MAX_ATTEMPTS=1` and `BANKING_TASKS_RETRY_DELAY=PT1S`,
perform one customer action, and watch the log and the table.

- [ ] The application log has one `ERROR` line carrying the task id, its type and the reason.
- [ ] The row is kept as `FAILED` with `last_error` set – it is not deleted.
- [ ] A `FAILED` row is never selected by the drain again, so it is not retried forever.

---

## 9. Automated tests

```bash
mvn -B test
```

The whole suite, with no Docker and no broker: the outbox tests run against the in-memory H2
database, and the publisher test drives a test double of `BackgroundTaskPublisher` so a nack,
a delay and an exhausted attempt count become assertions instead of hopes. The
Testcontainers test is skipped when Docker is unavailable.

| Test class                                                        | What it covers                                                                                       |
| ----------------------------------------------------------------- | ---------------------------------------------------------------------------------------------------- |
| `event/task/BackgroundTaskOutboxRecorderTest`                      | one `PENDING` row per action with the expected type and record, the requester and correlation id from the request, a second submission of the same task writing nothing, and no row left behind when the transaction rolls back |
| `event/task/BackgroundTaskOutboxPublisherTest`                     | `PUBLISHED` only after a successful publish, a failed publish staying `PENDING` with `attempts`/`last_error`/`next_attempt_at`, the retry succeeding on a later drain, `FAILED` + one `ERROR` line at `max-attempts`, and no second publish of a `PUBLISHED` row |
| `event/task/rabbit/RabbitBackgroundTaskPublisherTest`              | the adapter against a mocked template: the exchange and the type's routing key, a publish waiting for the confirm, and a nack or a returned message failing with the task id |
| `service/BackgroundTaskSubmissionTest`                             | each business flow writing its expected task row while the BANK-7 business-event row is still written |
| `event/task/rabbit/BackgroundTaskRabbitContainerTest`              | against a real broker in Testcontainers: the message in the right queue, survival across a broker restart, and recovery after an outage |
| `event/BrokerTypesNotReferencedInBusinessCodeTest`                 | business code still names no broker class – Kafka or RabbitMQ – and `BusinessEventBus` still declares `publish` and `subscribe` only |

Just the ones that need no broker:

```bash
mvn -B test -Dtest='BackgroundTaskOutboxRecorderTest,BackgroundTaskOutboxPublisherTest,RabbitBackgroundTaskPublisherTest'
```

The one that needs a real broker (Docker):

```bash
mvn -B test -Dtest='BackgroundTaskRabbitContainerTest'
```

---

## 10. Notes and limitations

- **Nothing is submitted from the request thread.** A business action returns as soon as its
  transaction commits; how long the task takes to reach its queue is the drain's business,
  one second at a time by default.
- **A publish is retried, not re-recorded.** The drain republishes the stored `payload` byte
  for byte, so the task keeps its `taskId` and `occurredAt` across attempts.
- **No consumer performs the tasks in this change.** The four queues hold the messages; the
  workers that send the email, build the statement, produce the export and scan the document
  are later work.
- **The Kafka business events are unchanged.** `business_event_outbox`, `BusinessEventBus`
  and everything in `docs/BANK-7-event-publishing-and-ordering.md` behave exactly as before;
  BANK-8 adds a second, independent store-and-forward path next to it.
- **No dead-letter exchange and no consumer-side retry.** There are no consumers, and a task
  that cannot be *sent* is kept as a `FAILED` row plus one `ERROR` line.
- **No HTTP endpoint inspects or replays the outbox.** A `FAILED` row is read from the table,
  the same choice BANK-7 made for its own outbox.
- **No metrics for submission.** BANK-9 owns eventing metrics; this change reports through
  log lines only.

---

## Sign-off

| # | Check                                                                                  | Result |
| - | -------------------------------------------------------------------------------------- | ------ |
| 1 | Each of the four task types is submitted to its own queue                              | ☐ |
| 2 | Each message names the task, the record, the requester and a correlation id             | ☐ |
| 3 | A task counts as submitted only once RabbitMQ confirmed it; transient failures are retried | ☐ |
| 4 | Queued tasks survive a RabbitMQ restart                                                | ☐ |
| 5 | Each task type has its own queue and routing rule, documented here                     | ☐ |
| 6 | Submitting the same task twice does not queue the work twice                           | ☐ |
| 7 | A customer action succeeds while RabbitMQ is down, and the task is queued when it returns | ☐ |
| 8 | `mvn -B test` covers send, confirmation failure, duplicate submission, restart and outage | ☐ |
| 9 | `docker compose -f docker-compose.rabbitmq.yml up -d` starts the local RabbitMQ        | ☐ |
| 10 | A reviewer sees real messages, with their envelope, in the real queues (section 7)     | ☐ |

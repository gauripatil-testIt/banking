# BANK-8 – Background task queues and how to run RabbitMQ locally

Slow work – the welcome email, statement generation, the CSV export and the malware scan – is no
longer done inside the request. It is written to an outbox row in the same transaction as the
business change, and a scheduled relay publishes that row to RabbitMQ. The caller is answered
immediately, and the work is on its own queue with its own routing rule, so one slow task type can
never hold up another.

This page lists the four queues and their business keys, shows the HTTP call for each submission,
and starts a local broker with **one** command so a reviewer can watch real messages land in real
queues.

BANK-8 delivers **producers only**. Nothing consumes these queues until BANK-11.

---

## 1. Start a local RabbitMQ

One command, with the management plugin so the messages are visible in a browser at
<http://localhost:15672> (user `guest`, password `guest` – the local development default):

```bash
docker run -d --name banking-rabbit -p 5672:5672 -p 15672:15672 rabbitmq:3.13-management
```

That is the only broker the application talks to locally: Spring Boot's own defaults
(`localhost:5672`, user `guest`) apply, so nothing has to be configured to run this on a workstation.
No broker host, credential or other connection setting is committed to the repository; a deployment
supplies `SPRING_RABBITMQ_*` from its environment.

## 2. Stop and remove it

```bash
docker stop banking-rabbit && docker rm banking-rabbit
```

---

## 3. What happens to a task

1. The business transaction that triggers the work (creating a customer, uploading a document,
   accepting an export, accepting a statement request) inserts one `background_tasks` row with
   status `PENDING`. The row carries the already serialised business event (the BANK-6 envelope) and
   its unique business key. If the business transaction rolls back, the row disappears with it.
2. `TaskOutboxRelay` runs every `banking.tasks.relay-interval` (2 seconds by default), in its own
   transaction, and publishes each due `PENDING` row through `RabbitTaskQueuePublisher`.
3. The publisher returns only when **RabbitMQ confirmed** the message (publisher confirms,
   `publisher-confirm-type=correlated`). Only then does the relay mark the row `SUBMITTED` with
   `published_at` set.
4. If the broker is unreachable, or the message is nacked or unroutable, the row stays `PENDING`
   with `attempts` incremented and `next_attempt_at` moved forward
   (`min(2^attempts, 60)` seconds). The user's request has already completed, and the next successful
   relay run hands the task to the broker – once.

The relay runs with the real RabbitMQ client: there is no stub, no in-memory fallback and no
no-op publisher in the application.

---

## 4. The exchange, the queues and the routing rules

One durable topic exchange, one durable queue per task type and one durable binding per queue. The
exchange, queue and routing-key names come from `banking.tasks.*` (defaults below); every
`BackgroundTaskType` has its own routing-key property, which is also what makes the four routing
rules distinct.

| Task type (`BackgroundTaskType`) | Event `type` on the wire | Exchange        | Routing key                   | Queue                              | Business key (dedupe)                  | Requester |
| -------------------------------- | ------------------------ | --------------- | ----------------------------- | ---------------------------------- | -------------------------------------- | --------- |
| `WELCOME_EMAIL`                  | `welcome.email.requested`| `banking.tasks` | `task.welcome-email`          | `banking.tasks.welcome-email`      | `<customerId>`                          | `system` (nobody is named on `POST /customers`) |
| `STATEMENT_GENERATION`           | `statement.requested`    | `banking.tasks` | `task.statement-generation`   | `banking.tasks.statement-generation`| `<accountId>:<periodStart>:<periodEnd>` | query parameter `requestedBy` |
| `DOCUMENT_EXPORT`                | `export.requested`       | `banking.tasks` | `task.document-export`        | `banking.tasks.document-export`    | `<exportType>:<exportedBy>` (`exportType` is `customers`, `accounts` or `both`) | query parameter `exportedBy` |
| `DOCUMENT_SCAN`                  | `document.scan.requested`| `banking.tasks` | `task.document-scan`          | `banking.tasks.document-scan`      | `<documentId>`                          | form field `uploadedBy` |

Every message is a BANK-6 `BusinessEvent` envelope, published persistent as `application/json`, with
`messageId` equal to the envelope's `eventId` and `correlationId` equal to the request's correlation
id:

```json
{
  "eventId": "3f1d2c9a-8b7e-4c6d-9f01-2a3b4c5d6e7f",
  "type": "document.scan.requested",
  "version": 1,
  "occurredAt": "2025-03-04T09:07:45Z",
  "subjectType": "CUSTOMER",
  "subjectId": 1,
  "causedBy": "ops.user",
  "correlationId": "9b1d0f7c-1c1e-4f0e-9d6a-6f9a1b2c3d4e",
  "payload": {
    "documentId": 1,
    "customerId": 1,
    "originalFileName": "sample-valid.pdf"
  }
}
```

Because the exchange, queues and bindings are durable and messages are published persistent, a task
that is already on a queue is still there after the broker restarts.

---

## 5. Submit each of the four tasks

Start the application (section 1 first) with its local defaults:

```bash
mvn spring-boot:run
```

Create a customer to work with – the same `id` is used below:

```bash
curl -i -X POST http://localhost:8080/customers \
  -H 'Content-Type: application/json' \
  -d '{"firstName":"John","lastName":"Doe","email":"john@example.com","phone":"1234567890","status":"ACTIVE"}'
```

### 5.1 Welcome email – `POST /customers`

The call above returned `201 Created` with `"id":1`. In the same transaction it queued a
`WELCOME_EMAIL` task with business key `1`. The response is unchanged from BANK-1, because the
welcome email is announced alongside customer creation rather than replacing it.

### 5.2 Document scan – `POST /customers/{customerId}/documents`

```bash
curl -i -X POST http://localhost:8080/customers/1/documents \
  -F "file=@docs/samples/sample-valid.pdf" \
  -F "documentType=ID_PROOF" \
  -F "uploadedBy=ops.user"
```

`201 Created` with the document body (unchanged from BANK-2/BANK-5); the scan was queued under
business key `<documentId>` in the same transaction, so a rejected upload queues nothing.

### 5.3 Document export – `GET /exports?type=…&exportedBy=…`

```bash
curl -i "http://localhost:8080/exports?type=customers&exportedBy=ops.user"
```

```json
{"taskId":1,"taskType":"DOCUMENT_EXPORT","status":"PENDING"}
```

`202 Accepted`, with `Location: /tasks/1`. No CSV is produced in the request any more – the
`ExportService.write` CSV builder is left for the BANK-11 worker.

### 5.4 Statement generation – `POST /accounts/{accountId}/statements`

An account is needed first:

```bash
curl -i -X POST http://localhost:8080/accounts \
  -H 'Content-Type: application/json' \
  -d '{"customerId":1,"accountNumber":"00012345","type":"SAVINGS","balance":1000.00,"status":"ACTIVE"}'
```

```bash
curl -i -X POST "http://localhost:8080/accounts/1/statements?periodStart=2025-02-01&periodEnd=2025-02-28&requestedBy=ops.user"
```

```json
{"taskId":2,"taskType":"STATEMENT_GENERATION","status":"PENDING"}
```

`202 Accepted`. An unknown account is the usual `404` ProblemDetail
(`"detail": "Account not found with id: 999"`) and queues nothing, and a missing period parameter is
a `400`.

---

## 6. Watch the message arrive

Within a couple of seconds (the relay interval) the task is on its own queue. Queue lengths first:

```bash
docker exec banking-rabbit rabbitmqctl list_queues name messages messages_ready
```

Expect exactly one queue to hold a message per submission above, and the other three to stay at `0`
– that is what "one slow task type cannot hold up another" means in practice.

To read a message body, ask the management API for one message of a queue. `ack_requeue_true` leaves
the message on the queue, so this is safe to repeat:

```bash
curl -s -u guest:guest -H 'content-type: application/json' \
  -X POST "http://localhost:15672/api/queues/%2F/banking.tasks.document-scan/get" \
  -d '{"count":1,"ackmode":"ack_requeue_true","encoding":"auto"}'
```

Swap `banking.tasks.document-scan` for `banking.tasks.welcome-email`,
`banking.tasks.document-export` or `banking.tasks.statement-generation` to see the other three.

---

## 7. Poll the task

The export and statement submissions answer with a task id; poll it:

```bash
curl -s http://localhost:8080/tasks/1
```

```json
{"taskId":1,"taskType":"DOCUMENT_EXPORT","status":"SUBMITTED","publishedAt":"2025-03-04T09:07:47.123456Z"}
```

`status` is `PENDING` and `publishedAt` is `null` until the broker confirmed the message; an unknown
id is `404` with `"detail": "Task not found with id: 1"`.

---

## 8. Submitting the same task twice

A second submission of the same work returns the **same** task id and publishes **one** message: the
unique constraint on `(task_type, business_key)` in the `background_tasks` table is what makes the
duplicate impossible, and a losing concurrent insert re-reads the winner's row.

```bash
curl -s "http://localhost:8080/exports?type=customers&exportedBy=ops.user"   # same taskId again
```

---

## 9. Settings

| Property                                | Environment variable                   | Default                            | Meaning |
| --------------------------------------- | -------------------------------------- | ---------------------------------- | ------- |
| `banking.tasks.exchange`                | `BANKING_TASKS_EXCHANGE`               | `banking.tasks`                    | The durable topic exchange tasks are published to. |
| `banking.tasks.welcome-email-routing-key` | `BANKING_TASKS_WELCOME_EMAIL_ROUTING_KEY` | `task.welcome-email`         | Routing key a `WELCOME_EMAIL` task is published with and its queue is bound with. |
| `banking.tasks.statement-generation-routing-key` | `BANKING_TASKS_STATEMENT_GENERATION_ROUTING_KEY` | `task.statement-generation` | Routing key a `STATEMENT_GENERATION` task is published with and its queue is bound with. |
| `banking.tasks.document-export-routing-key` | `BANKING_TASKS_DOCUMENT_EXPORT_ROUTING_KEY` | `task.document-export`    | Routing key a `DOCUMENT_EXPORT` task is published with and its queue is bound with. |
| `banking.tasks.document-scan-routing-key` | `BANKING_TASKS_DOCUMENT_SCAN_ROUTING_KEY` | `task.document-scan`        | Routing key a `DOCUMENT_SCAN` task is published with and its queue is bound with. |
| `banking.tasks.welcome-email-queue`     | `BANKING_TASKS_WELCOME_EMAIL_QUEUE`    | `banking.tasks.welcome-email`      | Queue for `WELCOME_EMAIL`. |
| `banking.tasks.statement-generation-queue` | `BANKING_TASKS_STATEMENT_GENERATION_QUEUE` | `banking.tasks.statement-generation` | Queue for `STATEMENT_GENERATION`. |
| `banking.tasks.document-export-queue`   | `BANKING_TASKS_DOCUMENT_EXPORT_QUEUE`  | `banking.tasks.document-export`    | Queue for `DOCUMENT_EXPORT`. |
| `banking.tasks.document-scan-queue`     | `BANKING_TASKS_DOCUMENT_SCAN_QUEUE`    | `banking.tasks.document-scan`      | Queue for `DOCUMENT_SCAN`. |
| `banking.tasks.relay-enabled`           | `BANKING_TASKS_RELAY_ENABLED`          | `true`                             | Whether the outbox relay runs at all. |
| `banking.tasks.relay-interval`          | `BANKING_TASKS_RELAY_INTERVAL`         | `2s`                               | How often the relay looks for pending work. |
| `spring.rabbitmq.publisher-confirm-type`| –                                      | `correlated` (set in `application.properties`) | A task counts as submitted only on a broker confirm. |
| `spring.rabbitmq.publisher-returns`     | –                                      | `true` (set in `application.properties`) | An unroutable message comes back instead of vanishing. |
| `spring.rabbitmq.template.mandatory`    | –                                      | `true` (set in `application.properties`) | Makes the return above happen. |
| `spring.rabbitmq.host` / `.port` / `.username` / `.password` | `SPRING_RABBITMQ_HOST` etc. | Spring Boot defaults (`localhost`, `5672`, `guest`/`guest`) | Broker connection; supplied by the environment in a deployment. |

---

## 10. Automated tests

```bash
mvn -B test
```

`BackgroundTaskEndToEndTest` drives the four endpoints over HTTP against a Testcontainers RabbitMQ
and therefore needs **Docker**; it is skipped rather than failed when Docker is unavailable. It
covers send, confirmation failure, duplicate submission, a broker restart and an outage.
`RabbitTaskQueuePublisherTest` and `RabbitTaskTopologyIntegrationTest` also use a real broker in a
container, while `TaskSubmissionServiceTest`, `TaskOutboxRelayTest` and `BackgroundTaskRepositoryTest`
need neither Docker nor a broker.

---

## 11. What BANK-8 does not do

* **No consumers.** The four workers (welcome email sending, statement PDF generation, the CSV
  export, the malware scan) are BANK-11. Nothing reads these queues yet, so messages stay on them
  until a worker is added.
* **No statement or export content in a request.** `ExportService.write` and the statement format
  exist in the codebase for the worker, but no endpoint returns generated report content.
* **No dead-letter queue, retention or purge policy** for `SUBMITTED` rows – BANK-11 defines the
  retry/DLQ strategy for consumers.
* **No authentication** on the new endpoints, and no re-publication of already stored data: this is a
  new table and existing documents and exports are untouched.

---

## Sign-off

| # | Check                                                                                  | Result |
| - | -------------------------------------------------------------------------------------- | ------ |
| 1 | One command starts a local RabbitMQ with the management plugin, and one stops and removes it | ☐ |
| 2 | One row per task type with event type, exchange, routing key, queue, business key and requester | ☐ |
| 3 | The HTTP call for all four submissions, the `202` with a task id and `GET /tasks/{taskId}` are shown | ☐ |
| 4 | Each call leaves a message in its own queue and in none of the other three              | ☐ |
| 5 | The page states BANK-8 publishes only and BANK-11 consumes                                | ☐ |
| 6 | No credential beyond the local development default host and `guest` user is written down | ☐ |

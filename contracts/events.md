# Event contracts

## CURRENT: submitted session

Producer: `exam-session-service` (`SessionService.publishSubmittedEvent`).
Consumer: `grading-service` (`GradingService.processSubmittedExam`).
Both declare a durable `exam.submitted.queue` through their local RabbitMQ config.

```text
Transport: RabbitMQ
Exchange: default exchange (RabbitTemplate.convertAndSend(queue, message))
Routing key / queue: exam.submitted.queue
Payload: one UUID as a string, e.g. 00000000-0000-4000-8000-000000000001
Meaning: session was manually submitted or auto-submitted after server timeout
```

Manual submission records SUBMITTED and removes Redis key
`exam_session:timeout:{sessionId}`. TTL expiration can call auto-submit and
record AUTO_SUBMITTED. Grading resolves the session through its public internal
API, then currently fetches live Question Bank details via `/questions/batch`.
The event does not carry answers or authorize a direct database read.

Consumer checks whether a result already exists for the session ID, and the
grading table has a unique session ID. Exceptions are currently caught/logged;
there is no demonstrated retry/outbox guarantee. Notification is sent inside
the submission transaction. Durable queue declaration alone does not prove
atomic transaction-to-message delivery or successful grading.

## REQUIRED / PROPOSED reliability behavior

- Repeated submission/event delivery must not create duplicate results or
  contradictory state; use stable session identity for idempotency.
- The producer's committed session must be readable before grading succeeds;
  transient read/delivery failure needs an observable retry/recovery policy.
- Timer expiry and manual submission must converge on one terminal session
  outcome. Errors must remain visible to the coordinator and owner.
- Grading must use the published exam's immutable snapshot/key/points, not a
  mutable Question Bank response. Keep correct answers server-side.
- These are acceptance requirements for Exam Taking; do not introduce brokers,
  extra infrastructure or an outbox in MAIN as part of setup.

## FUTURE: versioned events

There is currently no QuestionApproved/QuestionUpdated event contract. A future
versioned envelope might include event ID/type/version, UTC occurrence time and
session/exam identity; that would change the current plain-string consumer.
Negotiate migration and compatibility through MAIN before changing its payload
or queue. Do not assume one domain's status enum is an event schema shared by all.

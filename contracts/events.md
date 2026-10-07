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

## PROPOSED: grading result recorded (Analytics request ANALYTICS-GRADING-RESULT-EVENTS)

Requested by Analytics; provider is Grading (Exam Taking). Not implemented by
Grading yet. The Analytics consumer is implemented on `feature/reporting`
(`analytics-service/ANALYTICS.md`). The current `exam.submitted.queue` is a
point-to-point queue consumed by Grading and must not gain a second consumer.

```text
Transport: RabbitMQ, durable topic exchange examini.grading.events (declared by both sides)
Routing key: grading.result.recorded.v1
Content type: application/json, persistent delivery
Consumer queue: analytics.grading-result.queue (Analytics-owned, DLQ analytics.grading-result.dlq)
Publish: after commit (outbox, as Session does) whenever a result is created or changes
         (auto-grade, NEEDS_REVIEW, manual grade)
```

State-carried payload; each event is the complete current state of one result:

```json
{
  "eventId": "uuid", "schemaVersion": 1, "occurredAt": "2026-10-05T09:00:00Z",
  "resultId": "uuid", "resultRevision": 2,
  "sessionId": "uuid", "examId": "uuid", "candidateId": "uuid", "attemptNumber": 1,
  "status": "GRADED", "totalScore": 9, "maxScore": 10,
  "submittedAt": "2026-10-05T08:59:00Z", "gradedAt": "2026-10-05T09:00:00Z",
  "questions": [
    {"questionId": "uuid", "questionVersion": 3, "questionType": "MULTIPLE_CHOICE", "position": 0,
     "pointValue": 4, "answered": true, "needsReview": false, "awardedPoints": 4}
  ]
}
```

- `resultRevision` is monotonic per result (for example a JPA `@Version` on
  `ExamResult`); consumers apply only newer revisions, so duplicates, redelivery
  and reordering are safe. `sessionId` remains the logical-submission key.
- `questionVersion`, `position` and `pointValue` come from the frozen Exam
  snapshot used for grading, never from live Question Bank data.
- `awardedPoints` is null while `needsReview`; `totalScore` is required for `GRADED`;
  `gradedAt` is when the result became `GRADED` (null otherwise).
- `attemptNumber` may be null until Session's internal answers DTO exposes it.
- Never include answer content, option IDs, correct answers or grading keys.
- Provide a one-time replay of existing results (safe because consumers are idempotent).
- Known semantic gap: manual grading currently overrides only the total; item-level
  NEEDS_REVIEW questions stay pending in item analytics until Grading records
  per-question manual scores (non-blocking follow-up).

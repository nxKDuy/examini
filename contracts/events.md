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

## CURRENT: grading result recorded (Analytics request ANALYTICS-GRADING-RESULT-EVENTS)

MAIN đã duyệt và tích hợp producer Grading `a8ae421` + `e918493`, consumer
Analytics `d03730c` và Notification `0650a55`. `grading.result.recorded.v1`
truyền current grading state; publication Student là một boundary riêng, nên
Analytics có thể nhận trạng thái nháp/regrade trước khi Student thấy publication mới.
Dedup theo session/result identity và revision tăng, không tăng số logical result
khi release/regrade hoặc redelivery. Xem `manual-grading-api.md` cho visibility,
revision và Notification contract; ba frozen snapshot/identity Contract Requests
vẫn không chặn tích hợp.

Requested by Analytics; provider is Grading (Exam Taking). Producer and consumer
đã tích hợp trong MAIN (`analytics-service/ANALYTICS.md`). The current `exam.submitted.queue` is a
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
- `attemptNumber` lấy từ Session internal answers DTO đã triển khai; legacy
  consumer vẫn chấp nhận nullable theo v1.
- Never include answer content, option IDs, correct answers or grading keys.
- Historical replay trước khi consumer chạy là follow-up riêng, không tự phát lại
  thông báo Student. Grading outbox không replay các event đã đánh dấu published.
- Manual grading hiện lưu score từng item và phát outcomes tương ứng revision mới;
  không còn dùng total-only override cho workflow mới.

## CURRENT: Analytics attempt started + released score (ANALYTICS-SESSION-ATTEMPT-STARTED, ANALYTICS-GRADING-RELEASED-SCORE)

MAIN duyệt 2026-10-12. Additive v1; không đổi `grading.result.recorded.v1`,
`exam.submitted.queue`, `exam.attempt.invalidated`. Envelope chuẩn
`{eventId,eventType,eventVersion,occurredAt,payload}`, JSON persistent, outbox
sau commit. Consumer Analytics là task Reporting riêng (chưa bind).

### exam.attempt.started v1 (producer exam-session-service)

```text
Exchange: examini.events (topic)   Routing key: exam.attempt.started
eventId: UUID.nameUUIDFromBytes(UTF-8 "exam.attempt.started:v1:" + sessionId)
occurredAt = payload.startedAt
payload: {sessionId, examId, candidateId, attemptNumber, startedAt, expiresAt}
```

- Ghi vào `attempt_events` trong cùng transaction Start thành công; resume
  session đang IN_PROGRESS không phát lại. Không đi leg `exam.attempt.invalidated.queue`.
- attemptNumber ổn định, không tái dùng; invalidation vẫn chỉ qua
  `exam.attempt.invalidated` (không hoàn lượt). Consumer dedup theo eventId/sessionId.

### grading.result.published v1 (producer grading-service)

```text
Exchange: examini.grading.events (topic)   Routing key: grading.result.published.v1
eventId: UUID.nameUUIDFromBytes(UTF-8 "grading.result.published:v1:" + publicationId)
occurredAt = payload.releasedAt
payload: {publicationId, resultId, resultRevision, sessionId, examId, candidateId,
  attemptNumber, regraded, totalScore, maxScore, gradingMode, gradedAt, releasedAt,
  invalidated, questions:[{questionId, questionVersion, questionType, position,
  pointValue, answered, awardedPoints}]}
```

- Một event cho mỗi `ResultPublication` (release đầu `regraded=false`, mỗi regrade
  release `regraded=true`), cùng transaction tạo publication. Đây là snapshot đã
  công bố cho Student; khác `grading.result.recorded.v1` (trạng thái chấm hiện hành, có thể nháp).
- questionVersion/position/pointValue từ frozen Exam snapshot; awardedPoints và
  totalScore từ publication. Không có feedback (chung/từng câu), publicReason,
  ghi chú nội bộ, answer/answerDisplay, explanation, đáp án hay key.
- Consumer giữ bản `resultRevision` lớn nhất theo sessionId. Invalidation sau
  release dùng `exam.attempt.invalidated` (không phát lại published).
- Khác Notification `grading.result.released` trên `examini.events` (payload khác).

### Backfill một lần (mặc định TẮT)

eventId xác định nên replay an toàn; runner chỉ enqueue eventId chưa có trong outbox,
chạy lại không tạo trùng. Bật một lần rồi tắt sau khi log báo số event:

```text
exam-session-service: EXAM_EVENTS_BACKFILLSTARTED=true  (exam.events.backfill-started)
grading-service:      GRADING_EVENTS_BACKFILLPUBLISHED=true  (grading.events.backfill-published)
```

Log: `exam.attempt.started backfill enqueued N events` /
`grading.result.published backfill enqueued N events`. Backfill published lấy
`invalidated` theo trạng thái hiện tại của result.

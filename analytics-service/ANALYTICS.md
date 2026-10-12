# Analytics / Reporting (Phase 1)

Analytics owns **derived** reporting data only: aggregation, read-model projections and
analytics queries. It is never the source of truth for Exam lifecycle, Grading results,
Question lifecycle, student submissions or authorization membership.

## Data flow

```text
Grading (source of truth for results)
  └─ PROPOSED event grading.result.recorded.v1  ──► topic exchange examini.grading.events
        └─ queue analytics.grading-result.queue (DLQ analytics.grading-result.dlq)
              └─ GradingResultListener → ResultProjector (idempotent upsert)
                    └─ analytics_db: exam_result_projection, question_outcome_projection
                          └─ ExamAnalyticsQueries (SQL aggregation) → /analytics/exams/{examId}/…

Request authorization:
  Gateway → Analytics (verifies access JWT itself) → Exam GET /exams/{id}/internal/permission?manage=true
```

Analytics does not read any other service's database and does not call Grading. The old
`GET /analytics/exam/{examId}` route proxied a Grading endpoint (`/grades/exam/{id}/stats`)
that never existed; it was removed rather than adding aggregation to Grading, because
cross-result statistics are an Analytics responsibility.

**Provider dependency:** Grading does not publish this event yet (Contract Request
`ANALYTICS-GRADING-RESULT-EVENTS`, see `contracts/events.md`). Until it does, the endpoints
are correct but return zero-data results.

## Event contract consumed (PROPOSED v1)

State-carried: every event is the complete current state of one Grading result.

| Field | Meaning |
| --- | --- |
| `eventId`, `schemaVersion` (=1), `occurredAt` | Envelope; unknown versions are dead-lettered |
| `resultId`, `sessionId`, `examId`, `candidateId` | Identity; `sessionId` is the logical-submission key |
| `resultRevision` | Monotonic per result (e.g. JPA `@Version`); +1 on every change |
| `attemptNumber` | Session attempt number, nullable until Session exposes it to Grading |
| `status` | Grading status `PENDING` / `NEEDS_REVIEW` / `GRADED` |
| `totalScore`, `maxScore` | `maxScore` = sum of frozen snapshot points; `totalScore` required when `GRADED` |
| `submittedAt`, `gradedAt` | UTC instants; `gradedAt` = when the result became `GRADED` |
| `questions[]` | `questionId`, `questionVersion`, `questionType`, `position`, `pointValue`, `answered`, `needsReview`, `awardedPoints` |

No answer content, option IDs, correct answers or grading keys are carried.

## Idempotency and failure handling

- `exam_result_projection.session_id` is the primary key: one logical submission is one row.
- An event is applied only when its `resultRevision` is greater than the stored one, so
  duplicate deliveries, broker redelivery after restart and late older revisions are no-ops.
  Question outcomes for that result are replaced in the same transaction.
- Every delivery takes a transaction-scoped PostgreSQL advisory lock on its `sessionId`
  before the identity check, so even concurrent *first* deliveries are serialized.
- A changed `resultId`/`examId`/`candidateId` for a known session is rejected.
- `occurredAt` is required; numeric versions/revisions are parsed strictly (no 1.5 → 1).
- Contract violations (validation failures, malformed JSON) are dead-lettered immediately;
  infrastructure failures are retried 5 times with backoff, then dead-lettered.
- The projection can be rebuilt by truncating it and replaying every result event; the
  requested Grading publisher includes a replay/backfill for this (and for pre-existing results).

## Read APIs

All require an access JWT (Gateway route `/analytics/**`). Errors are `ProblemDetail`.

| Route | Response |
| --- | --- |
| `GET /analytics/exams/{examId}/summary` | `ExamSummary` |
| `GET /analytics/exams/{examId}/questions` | `QuestionStats[]`, ordered by snapshot position |
| `GET /analytics/exams/{examId}/results?status=&page=0&size=20` | Page of `CandidateResult` (newest submission first, size ≤ 100) |
| `GET /analytics/exams/{examId}/students?page=0&size=20` | Page of `StudentSummary`, one row per Student with a valid recorded result |

`/results` also accepts `candidateId` (UUID) and `invalidated` (boolean); filters compose
with `status` and apply to both `content` and `totalElements`. `/students` excludes invalidated
attempts. Both pages allow size 1..100. All four routes require Exam OWNER/MANAGER permission.

### Metric definitions

Exam summary:
- `registeredCount` is the distinct registered Student count (including revoked); `eligibleCount`
  is the current non-revoked Student count, both from the highest Exam recipient revision.
  Neither count measures who opened an attempt. `candidates` means distinct Students with at
  least one **valid recorded submission**, not all Students who started an attempt.
- `submissionRate` = `candidates / eligibleCount`, null without a participant projection or
  when `eligibleCount = 0`. This can exceed 1 after participant revocation; it is not clamped.
- `graded`, `needsReview`, `pending`, `submissions` are **attempt-level** counts of valid
  recorded results. `gradingCompletionRate` = `graded / submissions`, null for zero submissions.
  This measures Grading finalization, not Session completion or published results.
- `averageScore`, `minScore`, `maxScore`, `averagePercent` and `scoreDistribution` use
  **GRADED results only**; NEEDS_REVIEW totals are provisional and excluded.
- `scoreDistribution`: 10 buckets of percent of `maxScore`, `[0,10)…[90,100]`.
- `maxPossibleScore`: snapshot total from the results.
- Started/actual participants and started/completed Session attempts are not reported: the
  current events do not contain attempt-start or all terminal Session transitions.
- **Product scope lock (2026-10-12):** a published-score chart and published average/high/low
  must use the latest released valid attempt per Student. The current `grading.result.recorded.v1`
  event carries draft/current score only and no released revision or frozen published score.
  The existing `averageScore`, `minScore`, `maxScore`, `averagePercent` and `scoreDistribution`
  are legacy **current GRADED attempt** statistics and must not be shown as a published chart.
  Analytics needs a Grading-owned publication snapshot event before those fields can fulfill
  the locked product definition; see the contract gap reported to MAIN.

Question stats are keyed by the frozen snapshot item `(questionId, questionVersion)`, never
by the live Question Bank question:
- `attempts` results containing the item; `answered`, `correct`, `partial`, `incorrect`,
  `unanswered`, `pendingReview`.
- Outcome uses Grading's awarded points: `awarded = pointValue` → CORRECT, `0` → INCORRECT,
  in between → PARTIAL; unanswered → UNANSWERED; `needsReview` → PENDING_REVIEW.
- `correctRate = correct / (attempts − pendingReview)` (unanswered counts as not correct).
- `averageAwardedPoints` excludes PENDING_REVIEW and counts unanswered as 0.

Candidate results: `sessionId`, `resultId`, `candidateId`, `attemptNumber`, `status`,
`score` (null until GRADED), `maxScore`, `submittedAt`, `gradedAt`. Names are not stored;
a UI resolves display names through Auth.

## Authorization

Analytics exposes every candidate's score, so it follows Grading's submission policy:

| Actor | Behavior |
| --- | --- |
| Exam OWNER | Allowed |
| Exam MANAGER | Allowed |
| Exam PROCTOR | 403 (Exam `manage` permission excludes proctors, as for Grading submissions) |
| Unrelated TEACHER, ADMIN without membership | 403 (decided by Exam; persona is never global) |
| STUDENT | 403 before any lookup; no candidate-safe analytics endpoint exists |
| Missing/invalid/refresh token, `X-Service-Token`, forged `X-User-*` headers | 401 |
| Unknown exam | 404; Exam unavailable → 503 (fails closed) |

## Storage, performance, cache

Flyway `V1__analytics_projections.sql` (Analytics-only). Aggregation runs in PostgreSQL on
indexes `(exam_id, status)`, `(exam_id, submitted_at)` and `(exam_id, question_id, question_version)`;
full submission payloads are never loaded. Redis is intentionally not used: per-exam
aggregates over indexed projection rows are cheap at Phase 1 scale, and the database stays
the only durable store.

## Runtime configuration

`JWT_SECRET`, `ANALYTICS_EXAM_PERMISSION_TOKEN` (permission-only credential for the Exam
permission call; Analytics never receives `EXAM_INTERNAL_TOKEN`, which also unlocks grading
keys; until Exam accepts it, every request fails closed with 503), RabbitMQ connection
(`SPRING_RABBITMQ_*`). Compose wiring is a MAIN-owned change; see the MAIN handoff.

## Tests

`mvn -f analytics-service/pom.xml test` with a PostgreSQL database `analytics_test` on
`localhost:5432` (override with `ANALYTICS_TEST_DATABASE_URL/_USER/_PASSWORD`).

## Participant counts and invalidated attempts (APPROVED `contracts/participant-attempt.md`)

Consumed from the shared `examini.events` topic into `analytics.exam-events.queue`
(DLQ `analytics.exam-events.dlq`), bindings `exam.participant.changed` and
`exam.attempt.invalidated`. Envelope v1 is strict: `eventVersion` must be the JSON integer 1,
`occurredAt` an ISO-8601 instant, integer fields JSON integers, routing key = `eventType`;
anything else is dead-lettered without retry and does not consume its `eventId`.

- `exam.participant.changed`: latest snapshot per exam by highest `recipientRevision`
  (`exam_participation_projection`); older/equal revisions are ignored. `ATTEMPT_GRANTED`
  never changes counts. `registeredCount` must equal `eligibleCount + revokedCount`.
- `exam.attempt.invalidated`: one row per session in `attempt_invalidation` (reason/actor are
  validated but not stored). Aggregates exclude a result with `NOT EXISTS` against this table,
  so arrival order (before/after the result) and a missing result (CANCELLED) are all safe.
- `eventId` deduplication (`processed_event`) is written in the same transaction as the projection.
- Summary additions: `registeredCount`, `eligibleCount` (null until a snapshot exists, never 0),
  `invalidatedCount`, `submissionRate` = candidates with a valid submission / `eligibleCount`
  (null when eligible is null or 0). Invalidated results stay in `/results` with `invalidated: true`.
- Not provided: pass rate (no passing score in any event).

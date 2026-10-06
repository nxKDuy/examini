# MAIN decisions: Notification and Reporting

Decision date: 2026-10-05. These approvals cover contracts and shared wiring,
not completion of producer implementations or end-to-end runtime validation.

## Notification

Reviewed branch tip: `697b2a8` (service `c68f2bf`, shared wiring `9cf765b`).
MAIN approves the additive root module, Gateway `/notifications/**` route,
Compose service and fresh-volume `notification_db` initialization in `9cf765b`.
Existing database volumes need an explicit, non-destructive database creation.

MAIN approves the v1 envelope and five payloads proposed in that commit's
`contracts/notification.md`: durable topic exchange `examini.events`, routing
key equal to eventType, `notification.events.queue` and its dead-letter queue.
Publish through a transactional outbox after commit, with publisher confirms
and stable eventId on redelivery. Do not consume Grading's point-to-point
`exam.submitted.queue`. No answer keys, scores, private review notes or tokens.

Provider requests are approved, but require separate provider implementation:

| Request | Owner | Required capability |
| --- | --- | --- |
| NOTIF-QB-REVIEWED | Question Bank | question.reviewed from immutable review record; createdBy maps to ownerUserId |
| NOTIF-EXAM-MEMBER | Exam Taking | exam.member.invited for MANAGER/PROCTOR |
| NOTIF-EXAM-ASSIGNED | Exam Taking | exam.participant.assigned |
| NOTIF-SESSION-SUBMITTED | Exam Taking | session.submitted after committed terminal transition |
| NOTIF-GRADING-RELEASED | Grading / Exam Taking | grading.result.released only when result becomes GRADED |

Consumer merge decision: **FIX REQUIRED**. Validate required occurredAt as an
instant and integer eventVersion/questionVersion strictly. Coercion such as
fractional version 1.5 into 1 violates the approved version contract. Add
malformed-envelope regression tests. Correct the proposed frontend result
navigation to the SPA `/exam/result/{sessionId}`, not backend
`/grades/session/{sessionId}`. Inbox access remains token-subject only.

## Reporting

Reviewed branch tip: `af622ed` (service `43eef32`). MAIN approves the additive
`grading.result.recorded.v1` contract proposed in that commit's
`contracts/events.md`: durable topic exchange `examini.grading.events`, queue
`analytics.grading-result.queue`, dead-lettering, JSON schemaVersion 1,
stable eventId and monotonically increasing positive resultRevision.
Publish committed result state through an outbox and publisher confirms;
include frozen question versions/points and no answers or grading keys.
Creation, pending review and manual grading revisions must be reproducible.
Existing results require an explicit replay/backfill using the same contract.

Consumer merge decision: **FIX REQUIRED**. ResultProjector checks identity with
SELECT FOR UPDATE before INSERT, which does not lock an absent session row.
Two concurrent first deliveries with conflicting identities can bypass that
check. ON CONFLICT updates scores/revision while retaining the first identity;
question outcomes then use the second event's examId. Serialize by session
before checking identity, or use an equivalent atomic identity-safe mechanism.
Add a deterministic real-PostgreSQL conflicting-first-delivery regression test.
Also validate required occurredAt rather than accepting absent timestamps.

### Permission-only service credential

MAIN approves a separate `ANALYTICS_EXAM_PERMISSION_TOKEN`, restricted at the
Exam provider to `GET /exams/{id}/internal/permission` with `manage=true`.
Exam remains authoritative for OWNER/MANAGER access; PROCTOR, unrelated Teacher
and ADMIN without management membership are denied. TEACHER is not ADMIN.
Analytics must never receive the broad EXAM_INTERNAL_TOKEN, which also grants
access to grading snapshots/keys. Empty or unavailable permission integration
must fail closed. Never log or commit either credential.

Exam Taking owns provider-side restricted-token support. Reporting owns its
consumer configuration; MAIN owns Compose/env-example wiring after provider
support is verified. No shared entity or foreign database query is approved.

## Integration gates

### Notification re-review: approved with integration follow-ups

The Notification **FIX REQUIRED** decision above is resolved by
`0b63778c2d4d64c18d844772b4bd57381dc42ccd` and contract correction
`2163889d1278a3eb6a638c5915d8bd14567b8818`. MAIN inspected the complete fix diff
and independently reran `mvn -f notification-service/pom.xml test -q` against
isolated real PostgreSQL and RabbitMQ: 11 tests passed, no failures or errors.
The envelope validates required ISO instant, strict integer versions and object
payloads; malformed broker deliveries are dead-lettered without inbox records.
The SPA result route is corrected. The branch worktree was clean at review.

Notification is **APPROVED WITH FOLLOW-UPS / READY FOR MAIN INTEGRATION**.
This is not a merge or producer-to-inbox E2E result. Shared wiring/database
provisioning and the five provider emissions remain separate integration gates.
### Reporting re-review: approved with integration follow-ups

Reporting's **FIX REQUIRED** decision above is resolved by `d03730c`.
MAIN inspected the complete fix diff and independently ran
`mvn -B -f analytics-service/pom.xml package -q` against isolated real PostgreSQL:
13 tests passed, no failures or errors. A transaction-scoped session advisory
lock now precedes identity lookup; the upsert also guards identity equality.
The concurrent conflicting-first-delivery test verifies consistent result and
question identities. Required occurredAt validation and the dedicated
ANALYTICS_EXAM_PERMISSION_TOKEN configuration are implemented.

Reporting is **APPROVED WITH FOLLOW-UPS / READY FOR MAIN INTEGRATION**.
Tests use synthetic events and a mocked Exam permission client. Grading event
publication, permission-only provider support and real Gateway-backed E2E are
still unimplemented or unverified; empty configuration remains fail closed.

Neither business branch is merged by these decisions. Both consumer review
fixes have passed re-review; owners retain clean branches ready for integration.
MAIN then reconciles its pre-existing Analytics POM changes,
integrates shared wiring, provisions the database, refreshes shared Graphify
and validates real producer-to-consumer delivery through Gateway-backed APIs.
Unit/integration consumer tests with synthetic events do not prove producer E2E.
Existing Phase 2 provider work must not be interrupted to bypass these requests.

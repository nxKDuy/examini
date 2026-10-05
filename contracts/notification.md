# Notification contract

Status labels follow [README](README.md). Owner: Notification
(`notification-service/**`). Notification owns the inbox record, its wording
and read state; it never owns the business fact that triggered it.

## CURRENT: notification-service

Implemented on `feature/notification`. Port 8087, database `notification_db`,
Gateway route `/notifications/**` (shared wiring awaits MAIN approval).

No producer emits these events yet. The only existing message,
`exam.submitted.queue`, is a point-to-point queue on the default exchange with a
bare session ID consumed by Grading: a second consumer would steal Grading's
messages and the payload has no recipient. Notification does not use it.
Until a producer ships its event below, users receive no notification of that
kind; nothing is fabricated.

## REQUIRED / PROPOSED: event transport

```text
Exchange:     examini.events (topic, durable). Producers and consumer both declare it idempotently.
Routing key:  the eventType, e.g. question.reviewed
Content type: application/json, UTF-8
Consumer:     notification.events.queue (durable), dead letters -> notification.events.dlq
```

Envelope (version 1):

```json
{
  "eventId": "uuid, assigned once when the fact is recorded",
  "eventType": "question.reviewed",
  "eventVersion": 1,
  "occurredAt": "2026-10-05T10:00:00Z",
  "payload": { }
}
```

Producer requirements:

- Publish only after the source transaction commits (transactional outbox, as
  Session already does for submissions). Use publisher confirms.
- `eventId` is stored with the outbox row and reused for every redelivery. The
  consumer deduplicates on `(eventId, recipient)`; a regenerated ID would be a
  new notification.
- Payloads carry IDs and the minimum display text. Never include answers,
  answer keys, rubrics, scores, review notes, tokens or another user's data.
  Unknown extra fields are ignored and never stored.
- A breaking payload change needs a new `eventVersion`; unsupported versions
  are dead-lettered, not guessed, so they can be replayed after the consumer upgrades.

## REQUIRED / PROPOSED: Contract Requests (v1 payloads)

All fields are required. UUIDs are strings. `examTitle` is display text, max 150
characters after consumer truncation.

| Request ID | Provider | eventType | Trigger (existing transition) | Payload |
| --- | --- | --- | --- | --- |
| NOTIF-QB-REVIEWED | Question Bank | `question.reviewed` | `QuestionService.review` sets APPROVED/REJECTED | `questionId`, `questionVersion` (int >= 1), `ownerUserId`, `decision` (`APPROVED`\|`REJECTED`) |
| NOTIF-EXAM-MEMBER | Exam Taking (Exam) | `exam.member.invited` | `ExamCore.member` records `MANAGER_INVITED`/`PROCTOR_INVITED` | `examId`, `examTitle`, `userId` (invitee), `role` (`MANAGER`\|`PROCTOR`) |
| NOTIF-EXAM-ASSIGNED | Exam Taking (Exam) | `exam.participant.assigned` | `ExamCore.assign` adds an ASSIGNED participant | `examId`, `examTitle`, `userId` (student) |
| NOTIF-SESSION-SUBMITTED | Exam Taking (Session) | `session.submitted` | `SessionService.finish` (SUBMITTED / AUTO_SUBMITTED) | `sessionId`, `examId`, `examTitle`, `studentUserId`, `submissionType` (`SUBMITTED`\|`AUTO_SUBMITTED`) |
| NOTIF-GRADING-RELEASED | Exam Taking (Grading) | `grading.result.released` | Result becomes GRADED: auto grading, or manual grade of a NEEDS_REVIEW result | `sessionId`, `examId`, `examTitle`, `studentUserId`, `gradingMode` (`AUTO`\|`MANUAL`) |

Breaking change: no (additive; the existing `exam.submitted.queue` is
unchanged). Blocking: each row blocks only its own notification type.
Acceptance evidence: provider commit, outbox-after-commit test, stable eventId
across redelivery, and a message on `examini.events` consumed by
`notification-service` (see its integration test for the envelope).

Not proposed for Phase 1: exam published/opened fan-out to all participants
(needs a participant-list contract), invitation accepted, reminders, broadcast.

## CURRENT: API (current user only)

Every route requires an access JWT. The recipient is the token subject;
`X-User-Id`, `userId` query parameters, persona and service tokens grant
nothing. ADMIN sees only their own inbox. A foreign or unknown ID returns 404.

| Route | Response |
| --- | --- |
| `GET /notifications?page=0&size=20&read=true\|false` | Spring `Page<NotificationResponse>`; newest first (fixed, `sort` ignored); `size` max 50; `read` omitted = all |
| `GET /notifications/unread-count` | `{ "count": 3 }` |
| `PATCH /notifications/{id}/read` | `NotificationResponse`; idempotent, first `readAt` is kept |
| `PATCH /notifications/read-all` | `{ "updated": 2 }`; repeat returns 0 |

```text
NotificationResponse:
  id: UUID
  type: QUESTION_APPROVED | QUESTION_REJECTED | EXAM_MEMBER_INVITED | EXAM_ASSIGNED
        | SUBMISSION_RECEIVED | RESULT_AVAILABLE   (new types may be added; render unknown as generic)
  title, message: Vietnamese display text rendered by Notification
  entityType: QUESTION | EXAM | EXAM_SESSION
  entityId: UUID of that resource (no embedded foreign entity)
  metadata: string map of display fields (examTitle, role, questionVersion, decision, examId, ...)
  createdAt: ISO-8601 UTC instant
  readAt: ISO-8601 UTC instant or null
  read: boolean
```

## Frontend contract (for `frontend/app`, not implemented here)

Replace `MOCK_NOTIFICATIONS` in `frontend/src/features/dashboard/NotificationBoard.tsx`
through the shared API client (Bearer token, no identity headers):

- List: `GET /notifications?size=20` (use `content`, `totalElements`, `last`).
  Unread badge: `GET /notifications/unread-count`. Poll or refetch on focus;
  no push channel exists yet.
- Mark one: `PATCH /notifications/{id}/read`; mark all: `PATCH /notifications/read-all`.
- Relative time comes from `createdAt`. Icon by `type`. Navigation by
  `entityType`/`entityId`: QUESTION -> question detail, EXAM -> exam
  (invitation acceptance / lobby), EXAM_SESSION -> result `/grades/session/{entityId}`.
- Deleting a notification is not supported; remove the mock's dismiss action.

## Data model and delivery channels

Table `notifications`: `id`, `recipient_user_id`, `type`, `title`, `message`,
`entity_type`, `entity_id`, `metadata` (jsonb), `source_event_id`,
`source_event_type`, `created_at`, `read_at`. Unique
`(source_event_id, recipient_user_id)`; indexes for the inbox and unread count.

Only IN_APP exists; the row itself is the in-app delivery and read state.
FUTURE email/push would add a `notification_deliveries(notification_id, channel,
status, attempts)` table keyed by the existing notification ID, so notification
identity does not change. Preferences, digests and templates are not in scope.

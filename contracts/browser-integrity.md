# Browser Integrity — shared contract (locked 2026-10-12, MAIN 93f9482)

Provider details: `exam-session-service/PHASE2_BROWSER_INTEGRITY.md` (event types, metadata, ingestion, private review). This file locks the batch C additions for frontend.

## Scope lock (user-approved)
- Each Exam sets its own threshold: `IntegrityPolicy.warningEventLimit` (1..100, default 5). It is edited only on a DRAFT exam (`PUT /exams/{id}/integrity/policy`) and frozen at Publish.
- Budget signals (scope A, 2026-10-12): TAB_SWITCH, WINDOW_BLUR, EXIT_FULLSCREEN, PAGE_RELOAD. NETWORK_DISCONNECT/RECONNECT, SESSION_RESUME, FULLSCREEN_ENTER, WINDOW_FOCUS, COPY, PASTE are recorded for audit and never counted.
- One leave transition = one unit: a TAB_SWITCH, WINDOW_BLUR or EXIT_FULLSCREEN within ±1.5s (`exam.integrity.leave-coalesce-ms`, per session) of an already counted TAB_SWITCH/WINDOW_BLUR/EXIT_FULLSCREEN (switching tabs while fullscreen is one unit; PAGE_RELOAD always counts separately) is stored as COALESCED (linked to that unit) and not counted. The window and the unit choice use only the server receipt time (`receivedAt`); client `occurredAt` is stored as evidence and is used only to mark STALE (before Start), never to merge or uncount a new leave. Offline-buffered leave signals flushed together within the window therefore become one unit.
- Stale signals are recorded but never counted: `occurredAt` before the session start, or received after the session is no longer IN_PROGRESS / past `expiresAt`. These now return 200 with `counted=false, notCountedReason=STALE` (previously 409), so buffered events can be flushed after reconnect or submit. Policy-disabled types still return 409; dedup by `clientEventId` and 429 rate limits are unchanged.
- `POST /sessions/{id}/integrity/events` (session owner only) → `Receipt{event,duplicate,warningCount,warningLimit,warningRecommended,warning,autoSubmitted,counted,notCountedReason}`.
  - Additive fields: `counted` (this signal consumed one unit) and `notCountedReason` ∈ `NOT_BUDGET_SIGNAL | COALESCED | STALE` (null when counted). `warningCount` is the number of counted units in this session. Show the acknowledgment overlay with `warningCount/warningLimit` only when `counted=true`; a duplicate receipt echoes the stored `counted`.
  - When `warningCount == limit`, `warningRecommended=true`; show a warning that these signals are evidence, not proof of cheating.
  - When `warningCount > limit`, only this Session is AUTO_SUBMITTED (SYSTEM audit). The client stops the attempt and shows the submitted result state.
  - 429 means rate limited: retry with backoff. Never block answering or submitting.
- Whole-Exam lock: `PUT /exams/{id}/integrity/lock` (body `{reason?}`) and `PUT /exams/{id}/integrity/unlock`, for OWNER, MANAGER or PROCTOR.
  - Response: `IntegrityControl{examId,locked,unchanged,activeSessionsSubmitted,changedAt}`.
  - Lock blocks new Starts (409 `EXAM_INTEGRITY_LOCKED`; availability reason `EXAM_INTEGRITY_LOCKED`) and auto-submits every IN_PROGRESS session.
  - Unlock does not revive submitted sessions.
  - `ExamDto.Response.integrityLocked` exposes the state.
- Re-opening for an auto-submitted Student is done by granting a new attempt with a reason through the existing attempt-grant API (contracts/participant-attempt.md). There is no "undo auto-submit".
- Staff review: `GET /sessions/{id}/integrity/summary|events|audit`, `GET /sessions/exam/{examId}/integrity/events` (TEACHER/ADMIN with exam permission).

## Frontend rules
- Lock and unlock need a confirmation that states the number of active sessions that will be submitted. Locking requires a reason.
- Students never see other Students' events. Wording stays neutral ("tín hiệu", not "gian lận").

# Browser Integrity — shared contract (locked 2026-10-12, MAIN 93f9482)

Provider details: `exam-session-service/PHASE2_BROWSER_INTEGRITY.md` (event types, metadata, ingestion, private review). This file locks the batch C additions for frontend.

## Scope lock (user-approved)
- Each Exam sets its own threshold: `IntegrityPolicy.warningEventLimit` (1..100, default 5). It is edited only on a DRAFT exam (`PUT /exams/{id}/integrity/policy`) and frozen at Publish.
- Events that count: TAB_SWITCH, WINDOW_BLUR, EXIT_FULLSCREEN, PAGE_RELOAD, NETWORK_DISCONNECT. SESSION_RESUME is recorded but not counted.
- `POST /sessions/{id}/integrity/events` (session owner only) → `Receipt{event,duplicate,warningCount,warningLimit,warningRecommended,warning,autoSubmitted}`.
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

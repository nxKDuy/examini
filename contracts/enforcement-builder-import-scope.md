# PRODUCT SCOPE LOCK — Proactive Integrity, Exam Builder, Document Import, Vietnamese-first (2026-10-12)

The user approved this scope; MAIN base is a4ef92b. It extends `contracts/browser-integrity.md`, `contracts/participant-attempt.md`, `contracts/question-bank-lifecycle.md` and `contracts/exam-scheduling-scope.md`. Working features are not rebuilt.

## A — Proactive Browser Integrity (P0) · Exam Taking backend + APP
Approved decision: warnings require acknowledgment; network loss is not a violation.
- **Fullscreen (when `requireFullscreen`)**
  - The Start button click calls `requestFullscreen()` in the same user gesture, then calls Start. The question UI renders only when fullscreen is active AND the Start/Resume response succeeds.
  - Rejected or unsupported fullscreen: show a clear notice with a "Thử lại" button. Do not start the session. If the browser has no Fullscreen API, say so plainly.
  - On fullscreen exit: record one EXIT_FULLSCREEN and show a full blocking overlay. Answers cannot be edited and questions cannot be navigated until the student clicks "Quay lại toàn màn hình" and fullscreen is confirmed again (FULLSCREEN_ENTER recorded).
  - The timer keeps running from server time. Autosave and the deadline auto-submit are unchanged. If the deadline passes while blocked, the server auto-submits as usual.
  - Reload/resume: the overlay shows first, and the student must re-enter fullscreen with a click.
- **Tab switch / window focus loss**
  - One leave transition (tab hidden plus window blur within ~1.5s) counts as ONE violation.
  - On return, an acknowledgment overlay shows N/limit and neutral wording. The student clicks "Tôi đã hiểu" to continue. The timer keeps running.
  - The existing threshold auto-submit stays as it is: per Exam, default 5, only that student's session.
- **Network**
  - NETWORK_DISCONNECT/RECONNECT are recorded for audit but are **not counted** toward the auto-submit budget.
  - UI: a non-blocking "Mất kết nối – câu trả lời sẽ được lưu khi có mạng lại" banner. Pending answers retry on reconnect.
  - Events buffered offline are sent after reconnect, deduplicated by clientEventId.
- **Server requirements (Exam Taking)**
  - Coalesce TAB_SWITCH and WINDOW_BLUR from one transition into one budget unit, server-side, by time window per session.
  - Remove network events from the budget.
  - Accept the FULLSCREEN_ENTER type.
  - Stale client events: anything older than the session start or after a terminal status is recorded but never counted.
  - Keep dedup, rate limit and audit. Staff visibility is unchanged.
- Wording never claims the site can prevent fullscreen exit or control the OS. Signals are evidence, not proof of misconduct.

## B — Advanced Exam Builder (P0) · Exam Management (exam-service) + QB provider + APP
Approved decision: blueprint rows, shortfall is reported, no silent backfill.
- **Bulk selection (QB provider)**
  - `GET /questions/eligible?bankId&categoryId&questionType&difficulty&text&page&size` returns ACTIVE, exam-eligible questions the actor may use.
  - `POST /questions/eligible/ids` (same filters in the body, max 500) returns the ids for "select all matching".
  - `GET /question-sets/{id}/eligible-items` returns the eligible members of a set, plus a skipped list with reasons.
- **Selection (APP)**
  - Selection is kept across pages and filters; duplicates are prevented by questionId.
  - A selected-questions panel supports remove, reorder and per-question points, and shows the total.
- **Blueprint generation (exam-service)**
  - `POST /exams/blueprint/preview` with `{rows:[{categoryId?, questionType?, difficulty?, count, pointsPerQuestion}], shuffle, excludeQuestionIds?}`. The response has the picked questions per row, `shortfall` per row (requested vs available), the configuration summary and total points.
  - Picks are random within a row, never duplicated across rows, and never backfilled from another row.
  - The teacher can regenerate the whole draft or one row, swap or remove individual questions, then save through the existing exam create/update.
  - Eligibility and authorization are re-checked server-side on save.
  - The existing `/exams/auto` stays for compatibility.

## C — Document Import & OCR (P1) · Question Bank
Approved decision: all four formats, free local OCR, AI later.
- **Formats:** DOCX and text PDF (direct text extraction), PNG/JPG and scanned PDF (OCR via local Tesseract, vie+eng, used only when a page has no text layer). Limits: ≤20MB and ≤30 pages.
- **Staged flow:** Upload → Extract → Parse → Review → Edit → Validate → Import.
  - The batch is persisted as an import job with items.
  - Each item holds content, options, correct answers, explanation, type, subject/category, difficulty, a source reference (file + page) and a confidence score.
- **Review rules**
  - OCR text, low-confidence items and ambiguous items are flagged and must be reviewed.
  - A missing correct answer is never guessed. The item stays invalid until the teacher sets one.
- **Import:** only valid, reviewed items are imported, as Personal **DRAFT** with `creationMethod=IMPORT`, and an import audit is kept. Nothing is published or activated automatically.
- CSV import stays and may be routed into the same review flow.

## D — Vietnamese-first & Smart Defaults (P1) · APP
- **Language**
  - i18n infrastructure with Vietnamese as default and English as the second language, plus a language switch in the account/topbar menu.
  - A previously chosen language is kept per browser and per account.
  - Coverage: navigation, Exam Builder, monitoring warnings, validation messages, dialogs and notifications. No mixed-language hardcoded text.
- **New exam defaults (approved: enable all)**
  - Enabled: monitorTabSwitch, monitorWindowBlur, requireFullscreen, monitorNetwork (audit only), monitorCopy, monitorPaste.
  - warningPolicy WARN_ONLY, threshold 5.
- **Auto open/close:** preselected only when a valid schedule exists.
- Defaults are visible and editable. Existing or published exams and saved configurations are never changed.

## E — Minor issues (P2)
- **Two historical messages in `analytics.grading-result.dlq`** (Reporting): find the root cause, then either replay or quarantine them with an audit note.
- **Wrong history row for copy-sync version 4** (QB): add a correcting audit entry or an annotation. Never edit the row silently.
- **Incomplete order display in Set copy-sync** (QB + APP): expose the full source and local order.

## Validation and delivery
- Each worker runs targeted backend tests on real PostgreSQL. APP runs tsc, build, node tests and real-browser checks (fullscreen start, permission denied, exit and recovery, timer and deadline while blocked, tab/focus, network loss, reload/resume, threshold, session/student isolation, unsupported browser).
- Workers commit within their ownership and send an implementation handoff to MAIN. There is no separate full-system E2E phase.

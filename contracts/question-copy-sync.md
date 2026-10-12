# Question/Question Set Copy Version Sync — shared contract (locked 2026-10-12)

Provider contract: `question-bank-service/COPY_SYNC_CONTRACT.md` (QB, merged at MAIN 2e3b6b9, migration V11). Gateway forwards it through the existing `/questions/**` and `/question-sets/**` routes.

## Scope lock (user-approved)
- Selective apply: the user picks KEEP_LOCAL or TAKE_SOURCE per field, per `ITEM:<sourceQuestionId>` and `ORDER`. Conflicts (`conflict=true`, `reorderConflict=true`) are listed explicitly and must be resolved before apply. The frontend never auto-merges.
- If the source is removed, archived, revoked or out of access, preview returns `availability=UNAVAILABLE` and the screen is history-only (no apply button). A 409 on apply refreshes the preview.
- TAKE_SOURCE creates a new local DRAFT. The UI must say that the owner has to activate the new version, or the new DRAFT child Questions, before using it in an Exam or publishing it.

## Frontend rules
- `redacted=true` fields (OPTIONS, EXPECTED_ANSWER, EXPLANATION) show only "Nguồn đã thay đổi" / "Bạn đã sửa" from the flags. Never show values; there is no API for them.
- Apply always sends the four `expected*` values from the preview it rendered. On 409, reload the preview and keep the user's choices only for fields that still exist and are still changed.
- History tab: `GET …/copy-sync/history` (sync choices); content revisions stay at the existing `/versions/{version}` API.
- `404` = copy hidden or not accessible; show the generic not-found state.

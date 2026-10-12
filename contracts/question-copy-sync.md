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

## Additive fields (scope lock E, 2026-10-13; QB 38e3d40, adb0d61, migration V13)
Existing fields keep their names and meaning; these are only additions.
- **History correction.** Every `…/copy-sync/history` row now also has `correctedLocalVersion` (number or null), `correctionReason` (string or null) and `correctedAt` (time or null). `localVersion` is still the value recorded at apply time and is never rewritten. A correction comes from the append-only table `copy_sync_event_corrections`. The UI shows `correctedLocalVersion` as the effective version and labels the row "Đã đính chính", showing the reason and the recorded value.
  - Migration V13 created one correction automatically, for Question applies saved before bd974de that recorded the new DRAFT version plus 1. In the dev data this is fork `12599c78…`: the row recorded 4, and the version the apply actually created is 3.
- **Full Set order.** `GET/POST /question-sets/{id}/copy-sync` previews add `referenceOrder`, `sourceOrder` and `localOrder`. Each is an array of `{position, sourceQuestionId, localQuestionId, content}`.
  - `referenceOrder` is the order the copy was last synced against.
  - `sourceOrder` is the latest published source order. It is `[]` when `availability=UNAVAILABLE`.
  - `localOrder` is the current local Set version order.
  - `sourceQuestionId=null` means a local-only item. `localQuestionId=null` means there is no local copy of that source item yet.
  - `content` is the non-secret question stem, and it can be null for a reference item that was later removed. Options, keys and explanations are never included.
  - The existing booleans `sourceReordered`, `localReordered` and `reorderConflict` are unchanged.

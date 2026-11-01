# Exam Builder — blueprint preview contract (scope B, 2026-10-12)

Scope: `contracts/enforcement-builder-import-scope.md` §B. Provider: exam-service
(`BlueprintService`). Upstream: Question Bank `POST /questions/eligible/random`
and `POST /questions/batch?eligible=true` (`contracts/question-bank-api.md`).
`POST /exams/auto` is unchanged.

## POST /exams/blueprint/preview

TEACHER JWT (`403` otherwise). Nothing is persisted; the response is a draft the
teacher edits and then saves through the existing `POST /exams/manual` or
`PUT /exams/{id}` with `questions:[{questionId, weight}]`.

Request:

```json
{
  "rows": [
    {"bankId": null, "categoryId": "uuid?", "questionType": "MULTIPLE_CHOICE?",
     "difficulty": "EASY?", "count": 10, "pointsPerQuestion": 1.5,
     "pinnedQuestionIds": ["uuid", "..."]}
  ],
  "shuffle": true,
  "seed": 42,
  "excludeQuestionIds": ["uuid"]
}
```

- `rows`: 1..50 rows. `count` is 1..200, and the sum of all row counts is ≤200. `pointsPerQuestion` is >0 and ≤1000.
- `questionType` ∈ `MULTIPLE_CHOICE | TRUE_FALSE | SHORT_ANSWER | ESSAY`. `difficulty` ∈ `EASY | MEDIUM | HARD`. Filters are optional and AND-combined, and the provider applies them.
- `pinnedQuestionIds` (≤ `count`, unique across all rows): questions kept in this row. The server picks only `count - pinned`.
- `excludeQuestionIds` (≤1000): never picked. Use it for removed or swapped questions.
- `seed`: optional. It fixes the final shuffle order only; provider picks are random. When omitted, the server picks a seed and returns it.
- Errors:
  - `400`: validation failure, including a pinned id duplicated across rows, or more than 1000 exclusions including earlier rows' picks.
  - `503`: the Question Bank integration is not configured.
  - Provider errors are passed through.

Response `Preview`:

```json
{
  "rows": [{"rowIndex": 0, "bankId": null, "categoryId": "…", "questionType": "…", "difficulty": "…",
            "requested": 10, "picked": 8, "available": 8, "shortfall": 2, "pointsPerQuestion": 1.5,
            "droppedPinnedIds": [], "questions": [Question]}],
  "questions": [Question],
  "requestedQuestions": 10, "totalQuestions": 8, "totalPoints": 12.0,
  "totalShortfall": 2, "complete": false, "shuffle": true, "seed": 42
}
Question = {questionId, questionVersion, questionType, content, points, rowIndex, pinned}
```

- Each row is filled only from its own filters. `shortfall = requested - picked`. There is never a silent backfill from another row.
- Rows run in order. Each provider call excludes `excludeQuestionIds`, all pinned ids and every id already picked, so an id is never duplicated across rows.
- `available` = pinned ids kept plus the provider's matching eligible count after exclusion.
- Pinned ids are re-validated against the provider's eligible batch for the actor. Ids that are no longer eligible go to `droppedPinnedIds`, and their slot is refilled within the same row.
- A picked id that the eligible batch no longer confirms is omitted and counted as shortfall.
- `questions` is the flattened exam order: row order, or shuffled with `seed` when `shuffle=true`. `content` is the Safe question text, with no answers or keys.

### Regenerate flows (APP)
- **Whole draft:** resend the rows without `pinnedQuestionIds`, optionally with `excludeQuestionIds`.
- **One row:** pin every other row's current ids and leave the target row unpinned.
- **Swap or remove one question:** pin the rest of the row and add the removed id to `excludeQuestionIds`.
- **Remove without replacement:** lower that row's `count` and pin what remains.

## Save-time re-check (`POST /exams/manual`, `PUT /exams/{id}`)
- Every question being added is re-checked through `POST /questions/batch?eligible=true&includeAnswers=false` as the saving actor. That means all questions on create, and on update only the ids not already in the exam.
- If any id is not confirmed eligible, the save fails with `409 QUESTION_NOT_ELIGIBLE: [ids]`.
- Already-saved questions are still validated at Publish (frozen snapshot, unchanged).
- Authorization is unchanged: create requires TEACHER, and update requires OWNER or MANAGER on a DRAFT.

## Exam matrix (UI over the blueprint)

The matrix (topics × EASY/MEDIUM/HARD, points per level) is a client-side editor. Availability per cell comes from
Question Bank `POST /questions/eligible/matrix-counts`. Generating turns every non-empty cell into one blueprint row
(`categoryId`, `difficulty`, `count`, `pointsPerQuestion`), so `POST /exams/blueprint/preview` keeps its rules: a cell
is filled only from its own topic and level and reports its shortfall. Saved matrices are per-teacher browser storage.

## Paper exam

`GET /exams/{id}/paper` (OWNER/MANAGER only; 409 while DRAFT) returns the frozen snapshot **with answer keys**
(`Snapshot` incl. `options[].correct`, `expectedAnswer`, `explanation`), ordered by position, and audits
`PAPER_EXPORTED`. The print page mixes exam codes client-side with a generator seeded by `examId:code`, so a code always
reprints identically; answer keys and bubble answer sheets are derived from the same order.

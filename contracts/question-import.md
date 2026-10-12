# Question Bank — Document Import & OCR (scope lock C)

Status: **PROPOSED by Question Bank provider, 2026-10-12** (base MAIN `251d620`,
`contracts/enforcement-builder-import-scope.md` §C). Provider: `question-bank-service`
(`QuestionImportController`, `QuestionImportService`, migration `V12`). Consumer: APP.
CSV `POST /questions/import/csv` stays unchanged.

## Access

User JWT only (TEACHER/ADMIN author). A job and its items are visible only to the
uploader; anyone else gets 404. Service token is rejected (403). Every route is
under `/question-imports` (gateway route required, same JWT rules as `/questions`).

## Limits and formats

| Kind | Accepted | Extraction |
| --- | --- | --- |
| `DOCX` | `.docx` (OOXML) | Apache POI paragraph text (tables read row by row). Page = count of rendered/explicit page breaks + 1 (best effort, Word's own pagination). |
| `PDF` | `.pdf` | PDFBox text layer per page. A page with no usable text layer (< 20 non-space chars) is rendered at 300 DPI and OCR'd. |
| `IMAGE` | `.png`, `.jpg`, `.jpeg` | OCR (1 page). |

- File ≤ **20 MB** (413/400 otherwise), ≤ **30 pages** (400 `Tài liệu vượt 30 trang`), at most 300 items per job.
- OCR: local Tesseract CLI, languages `vie+eng`, only on pages without a text layer. No network/AI.
- Type is detected from content (magic bytes), not only the extension; unsupported → 400.
- Processing is asynchronous. Upload returns at once with `status=PROCESSING`; poll `GET /question-imports/{id}` (suggested 1–2 s).

## Recognised template (Vietnamese and English keywords, case-insensitive)

```text
Câu 1: <nội dung, có thể nhiều dòng>        (also "Câu 1.", "Question 1:", "Bài 1:")
A. <lựa chọn>                               (A–H; "A)", "A." or "A:"; one per line)
B. <lựa chọn>
C. <lựa chọn>
D. <lựa chọn>
Đáp án: B                                   (also "Answer:", "ĐA:"; letters or text)
Giải thích: <giải thích, có thể nhiều dòng>  (also "Explanation:", "Lời giải:")
Mức độ: Dễ | Trung bình | Khó               (optional; also "Difficulty: EASY|MEDIUM|HARD")
```

- Options present → `MULTIPLE_CHOICE`; exactly two options "Đúng"/"Sai" (or True/False) → `TRUE_FALSE`.
- No options and an `Đáp án:` text → `SHORT_ANSWER` with `expectedAnswer`.
- No options and no answer → `ESSAY`, flagged `NO_ANSWER_ESSAY` (must be reviewed).
- **A missing answer is never guessed.** An item with options but no `Đáp án` has no correct option and is invalid (`MISSING_CORRECT_ANSWER`) until the teacher sets one.
- Difficulty: parsed value, else the job default (`defaultDifficulty` at upload, default `MEDIUM`). Category: job `categoryId` if given, else empty (invalid until set).

## State

Job `status`: `PROCESSING` → `READY` | `FAILED`; `READY` → `DISCARDED` (DELETE). Import may run several
times on a `READY` job (only not-yet-imported items).

Item fields (owner view, includes keys — this is the author's own draft):

```text
ImportItem {
  id, position, version (optimistic lock, starts 1),
  content, questionType, difficultyLevel, categoryId?,
  options: [{content, correct, displayOrder}], expectedAnswer?, explanation?,
  source: {fileName, page (1-based, null if unknown), method: TEXT|OCR},
  confidence: 0.00–1.00, rawText,
  flags: [OCR, LOW_CONFIDENCE, MISSING_CORRECT_ANSWER, ANSWER_NOT_IN_OPTIONS, MULTIPLE_ANSWERS,
          DUPLICATE_OPTION, OPTION_SEQUENCE_GAP, NO_ANSWER_ESSAY, CONTENT_TRUNCATED],
  reviewRequired,   // true if OCR, confidence < 0.80, or any flag other than MISSING_CORRECT_ANSWER
  reviewed,         // set only by the teacher (PUT reviewed=true)
  excluded,         // teacher can exclude an item from import
  valid, errors: [{code, message}],   // same rules as manual create (QuestionValidation) + category required
  importable,       // valid && !excluded && !imported && (!reviewRequired || reviewed)
  importedQuestionId?, importedAt?
}
```

`confidence` is a heuristic: text layer starts at 1.00, OCR starts at Tesseract's mean
word confidence of the item's lines; each structural problem lowers it.

## Routes

| Route | Request | Response |
| --- | --- | --- |
| `POST /question-imports` | multipart `file`; optional `categoryId`, `defaultDifficulty` | 202 `ImportJob` (`status=PROCESSING`) |
| `GET /question-imports` | `page`, `size` ≤ 100 | Page of `ImportJob` (own, newest first) |
| `GET /question-imports/{id}` | — | `ImportJob` |
| `GET /question-imports/{id}/items` | optional `filter` = `ALL` (default) \| `NEEDS_REVIEW` \| `INVALID` \| `IMPORTABLE` \| `IMPORTED` \| `EXCLUDED`; `page`, `size` ≤ 100 | Page of `ImportItem` ordered by `position` |
| `PUT /question-imports/{id}/items/{itemId}` | `{version, content?, questionType?, difficultyLevel?, categoryId?, options?, expectedAnswer?, explanation?, reviewed?, excluded?}` (null = unchanged; `options` replaces all; `expectedAnswer`/`explanation` `""` clears) | `ImportItem` re-validated, `version+1`. Stale `version` → 409. Imported item → 409. |
| `POST /question-imports/{id}/validate` | — | `ImportSummary` (re-validates every item) |
| `POST /question-imports/{id}/import` | `{itemIds?: UUID[], bankId?}` (omitted = all importable; bank = default Personal) | `ImportResult {imported: [{itemId, questionId}], skipped: [{itemId, reason}], summary}` |
| `DELETE /question-imports/{id}` | — | 204; job → `DISCARDED`, items no longer editable; already imported questions are kept |

```text
ImportJob { id, fileName, fileKind: DOCX|PDF|IMAGE, fileSize, pageCount, ocrPageCount,
            status, failureReason?, warnings: [string], categoryId?, defaultDifficulty,
            createdAt, updatedAt, summary: ImportSummary }
ImportSummary { total, valid, invalid, needsReview, importable, imported, excluded }
```

`skipped.reason` ∈ `INVALID`, `NEEDS_REVIEW`, `EXCLUDED`, `ALREADY_IMPORTED`, `NOT_IN_JOB`.

## Import result

Each imported item becomes a **Personal DRAFT** Question in the actor's Personal bank:
`creationMethod=IMPORT`, `sourceType=USER_DOCUMENT`, `sourceLabel="<file> · trang <n>"`,
`sourceRefId="import:<jobId>:<itemId>"`, `sourceExcerpt=rawText` (private, never in Safe DTO),
`explanation` as edited. Nothing is activated or published. The provider keeps an audit
trail (`question_import_events`: UPLOADED, EXTRACTED/FAILED, ITEM_UPDATED, VALIDATED,
IMPORTED, DISCARDED with actor and time); the import is atomic per request.

## Errors

400 invalid file/limits/body, 404 unknown or foreign job/item, 409 stale version, job not
`READY`, or item already imported, 403 non-author or service token.

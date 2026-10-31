# Question Bank — Document Import & OCR (scope lock C)

Status: **LOCKED by MAIN, 2026-10-12** (proposed by Question Bank provider) (base MAIN `251d620`,
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

- **OCR line merges (additive, 2026-10-14, QB `0993a26`).** Boundaries found in the middle of one OCR line are split into separate items: a capitalised `Câu N:` (also the OCR variants `Cau`, `CÂU`, `Càu`, `Câu3`), `Đáp án:` / `Giải thích:`, and inline `A. … B. …`.
  - A bare `N.` starts a question only when it is the next number in sequence and the previous question already has its answer or explanation. Such an item is flagged `QUESTION_SPLIT_UNCERTAIN`.
  - When the provider is unsure, it does not guess; it flags the item for review. `NUMBERING_GAP` covers numbers that skip or go backwards. `QUESTION_SPLIT_UNCERTAIN` covers `Câu l` with a letter read in place of a digit, and an out-of-sequence `N.` that is kept as text. `POSSIBLE_MERGED_QUESTIONS` covers a second `Đáp án`, options restarting at `A`, or text after a complete letter answer (a lost header). In that last case the first answer is kept and nothing is re-split.
  - Clients must treat unknown flag values as "needs review".
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
  id, position, version (optimistic lock, starts 0),
  content, questionType, difficultyLevel, categoryId?,
  options: [{content, correct, displayOrder}], expectedAnswer?, explanation?,
  source: {fileName, page (1-based, null if unknown), method: TEXT|OCR},
  confidence: 0.00–1.00, rawText,
  flags: [OCR, LOW_CONFIDENCE, MISSING_CORRECT_ANSWER, ANSWER_NOT_IN_OPTIONS, MULTIPLE_ANSWERS,
          DUPLICATE_OPTION, OPTION_SEQUENCE_GAP, NO_ANSWER_ESSAY, CONTENT_TRUNCATED,
          QUESTION_SPLIT_UNCERTAIN, NUMBERING_GAP, POSSIBLE_MERGED_QUESTIONS],   // last three added 2026-10-14, additive
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

## AI generation (additive, 2026-10-29)

Teachers can ask an AI provider to write questions. The result is a job of `fileKind=AI` that goes
through exactly the same review, validate and import routes as a document job. Nothing reaches a bank
until the teacher reviews it.

| Route | Request | Response |
| --- | --- | --- |
| `GET /question-imports/ai/status` | — | `AiStatus { available, model?, maxQuestions: 20, maxMaterialChars: 20000 }` |
| `POST /question-imports/ai` | JSON `AiGenerateRequest` | 202 `ImportJob` (`status=PROCESSING`, `fileKind=AI`) |

```text
AiGenerateRequest { topic (1–500), material? (≤ 20000 chars, untrusted knowledge text), categoryId (required, existing),
                    count? 1–20 (default 5), questionTypes? 1–4 of QuestionType (default [MULTIPLE_CHOICE]),
                    difficulty? (default MEDIUM, a target only), language? vi|en (default vi), useBankExamples? (default true) }
ImportJob.generation (null on document jobs) = AiGeneration { topic, model, requestedCount, questionTypes, difficulty,
                    language, hasMaterial, materialLength, exampleCount }
```

- Providers, tried in `QB_AI_PROVIDERS` order (default `gemini,openai,ollama,local`): Gemini (`GEMINI_API_KEY`, `GEMINI_MODEL`),
  any OpenAI-compatible API (`AI_OPENAI_URL`, `AI_OPENAI_KEY`, `AI_OPENAI_MODEL` — e.g. Groq, OpenRouter `:free`, Mistral, a
  local LM Studio/vLLM), Ollama (`OLLAMA_URL`, `OLLAMA_MODEL`) and the rule-based `local` generator (no network). Rate-limited
  providers cool down and are skipped; `status.providers` lists each with `available`/`coolingDown`.
- One AI job `PROCESSING` per teacher (429 otherwise). Same owner-only access as other jobs (404 for others, 403 non-author).
- **Bank context.** With `useBankExamples`, up to 15 of the actor's **own** non-archived questions in the category are sent as
  examples. Duplicate checking uses up to 2000 of the actor's own questions in that category. Nothing from other owners,
  organizations or the public bank is sent.
- Material and examples are fenced as data in the prompt; instructions inside them are ignored by design.
- **Never guessed.** Items keep the AI's answer key as given. No correct option → `MISSING_CORRECT_ANSWER`, more than one →
  `MULTIPLE_ANSWERS`; nothing is promoted to correct. Choice questions drop any `expectedAnswer`.
- Every item: `source.method=AI`, `source.page=null`, flag `AI_GENERATED`, `reviewRequired=true`, `confidence` 0.70 minus 0.15
  per structural flag. `POSSIBLE_DUPLICATE` marks a case/accent/punctuation-insensitive match with an existing own question
  in the category or an earlier item of the same job, or a near-copy (character-bigram similarity ≥ 0.88 for stems of 20+
  characters). `difficultyLevel` is the AI's per-item estimate (fallback: the request).
- **Quality gate** (rule-based, review flags only, each lowers `confidence` by 0.15): `SOURCE_REFERENCE` ("theo đoạn văn trên",
  "according to the passage" in a stem ≤ 400 characters), `CATCH_ALL_OPTION` ("tất cả các đáp án trên", "none of the above"),
  `LONGEST_IS_KEY` (single-answer key ≥ 25 characters and ≥ 1.5× every distractor),
  `WEAK_DISTRACTORS` (worded options ≥ 90 % alike or one inside another; numeric options in the same unit within 3 % of each other),
  `ANSWER_IN_STEM` (the keyed option's words appear in the stem and no distractor's do). `DUPLICATE_OPTION` compares
  options case/space-insensitively but keeps accents and symbols (`x = 1` ≠ `x = −1`).
- **Key cross-check** (`QB_AI_VERIFY_KEYS`, default true; skipped for `local` output): the first available language model
  re-solves up to 40 choice/statement questions without the key. A different answer adds `KEY_DISPUTED` (confidence −0.2);
  the job warnings summarise the result. When no model answers, items are unchanged and a warning says so.
- `rawText` is the item's JSON as returned by the AI (provenance). Job `warnings` cover truncated output and fewer/extra items.
- Provider failures make the job `FAILED` with a readable `failureReason`; internal details are not exposed.
- Import of an AI item creates a Personal DRAFT with `creationMethod=AI_GENERATED`, `sourceType=USER_DOCUMENT` when material
  was given else `NONE`, `sourceLabel="AI · <topic> · <model>"`, `sourceRefId="ai:<jobId>:<itemId>"`, `sourceExcerpt=rawText`.
- Audit events add `AI_REQUESTED` (parameters, no material text) and `AI_GENERATED`.
- Migration `V14`: `file_kind` accepts `AI`, `source_method` accepts `AI`, new nullable `question_import_jobs.generation`.

## Spreadsheets, templates, AI material and Bloom level (additive, 2026-10-30)

`FileKind` adds `XLSX` and `CSV`. See `question-media-and-skills.md` for `GET /question-imports/templates/{excel|word}`,
`POST /question-imports/ai/material` and `AiGenerateRequest.cognitiveLevel`.

## AI material from a web link (additive, 2026-10-31)

`POST /question-imports/ai/material-url` body `{ "url": "https://…" }` (author roles only, AI rate limit at the gateway)
returns the same `MaterialText` as the file route: `fileName` = page title, `text` = readable article text (scripts, menus,
banners, footnotes and edit links removed; formulas kept once as TeX) or the text of an online PDF, `warnings` ends with
`Nguồn: <final URL>`. Nothing is stored. 400 with a Vietnamese reason for: non-http(s) scheme, credentials in the URL,
a port other than 80/443, a name without a dot or ending in `.local`/`.internal`, any address that resolves to loopback,
private, link-local (cloud metadata), CGNAT, multicast or unique-local ranges, more than 3 redirects (each hop re-checked),
a body over 3 MB, a type other than HTML/text/PDF, or a page with almost no text. Timeouts: connect 5 s, read 10 s
(properties `question-bank.ai.web-connect-ms` / `question-bank.ai.web-read-ms`).

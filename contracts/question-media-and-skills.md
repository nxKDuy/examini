# Question media, passages and the four skills (additive, 2026-10-30)

Owner: question-bank-service (media, presentation), exam-service (snapshot), exam-session-service (voice answers),
grading-service (speaking review). Everything here is additive: old clients ignore `presentation` and old questions
have none.

## Question types

`MULTIPLE_CHOICE | TRUE_FALSE | SHORT_ANSWER | ESSAY | SPEAKING`. `SPEAKING` has no options; the student records a
voice answer; grading always waits for a teacher (empty answer = 0). `expectedAnswer` of ESSAY/SPEAKING is the grading
guide, never shown to students.

## Presentation (optional, on create/update/response, frozen into exam snapshots)

```json
{
  "media": [{ "id": "uuid", "kind": "IMAGE|AUDIO", "name": "chart.png" }],
  "optionMedia": { "0": "image-uuid" },
  "passage": { "key": "uuid", "title": "…", "content": "…", "media": [ … ] },
  "maxPlays": 2,
  "minWords": 40, "maxWords": 80,
  "maxRecordSeconds": 120
}
```

- `media` ≤ 10, passage `media` ≤ 5, passage title ≤ 200, content ≤ 20000 chars.
- `optionMedia` is keyed by option `displayOrder` (MULTIPLE_CHOICE); every key must match an option.
- Questions with the same `passage.key` form one reading/listening group: the online attempt keeps them adjacent when
  shuffling, the paper exam moves them as one block, and the passage is shown with each of them.
- `maxPlays` 1..10, only when there is audio (enforced by the client per attempt tab; a listening limit, not security).
- `minWords`/`maxWords` 0..5000, ESSAY only. `maxRecordSeconds` 10..600, SPEAKING only (default 120).

## Routes (gateway → question-bank-service)

| Route | Body | Result |
|---|---|---|
| `POST /question-media` | multipart `file` (image ≤ 3 MB: PNG/JPEG/WEBP/GIF; audio ≤ 20 MB: MP3/M4A/OGG/WAV/WEBM, sniffed from bytes) | `{ id, kind, contentType, size, name }`, author only |
| `GET /question-media/{id}` | — | the bytes; any signed-in user (ids are only reachable through questions/snapshots) |
| `GET /questions/passages` | — | the caller's distinct passages, newest first (≤ 300 questions scanned) |
| `GET /question-imports/templates/{excel\|word}` | — | sample XLSX (sheet "Câu hỏi" + "Hướng dẫn") or DOCX in the `Câu N:` format |
| `POST /question-imports/ai/material` | multipart `file` (DOCX, PDF, PNG, JPG) | `{ fileName, text, pageCount, ocrPageCount, truncated, warnings }`; text cut at 20000 chars; nothing stored |

## Voice answers (gateway → exam-session-service)

| Route | Body | Result |
|---|---|---|
| `POST /sessions/{id}/answers/{questionId}/audio?seconds=n` | raw recording, `Content-Type: audio/webm\|ogg\|mp4\|mpeg` (≤ 12 MB, seconds ≤ limit + 5) | `{ questionId, seconds, bytes, type }`; owner, attempt in progress, SPEAKING only |
| `GET /sessions/{id}/answers/{questionId}/audio` | — | the recording; the student or staff of the exam |

The stored answer becomes `{"audio":true,"seconds":n,"bytes":n,"type":"audio/webm"}`; `POST /sessions/{id}/answers`
rejects text for SPEAKING. Grading `GET /submissions/{id}` now carries `sessionId` so the grader can fetch the recording.

## AI generation

`AiGenerateRequest.cognitiveLevel` (optional): `REMEMBER | UNDERSTAND | APPLY | ANALYZE | EVALUATE | CREATE | MIXED`
(Bloom). `questionTypes` accepts up to 5 types including `SPEAKING`.

## Import formats

XLSX and CSV (`,` or `;`, UTF-8, optional BOM): one question per row, header aliases `Câu hỏi, Loại, Độ khó, A–H,
Đáp án, Giải thích`. Word/PDF text accepts an extra `Loại:` line (`Tự luận`, `Nói`). Rows are flagged, never guessed.
The web export "Excel / CSV" writes the same columns, so an export imports back unchanged; it also exports Moodle XML
and Aiken.

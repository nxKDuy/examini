# Question Bank public API

## CURRENT: source and access

Inspected baseline: commit `2b07f15`. Sources:
`question-bank-service/src/main/java/com/datn/questionbank/controller/{Question,Category,Subject}Controller.java`,
`dto/QuestionDto.java`, `entity/Question.java`, `service/QuestionService.java`,
`repository/QuestionRepository.java` and the gateway's `JwtAuthFilter.java`.

The service name is `question-bank-service`; its configured port is 8082.
Clients use Eureka/Feign. Browser traffic goes through gateway port 8080, with
the same `/subjects`, `/categories`, `/questions` paths. Gateway validates
Bearer JWTs and supplies `X-User-Id`, `X-User-Role`, `X-User-Email`.
Current provider controllers do not enforce review roles or a student-safe
projection; do not assume gateway authentication hides correct answers.

## CURRENT: routes

| Route | Request | Response / behavior |
| --- | --- | --- |
| `GET /questions/{id}` | UUID path | `QuestionResponse`, 200; missing ID -> 404 |
| `GET /questions` | optional `categoryId`, `difficulty`; Spring `page`, `size`, `sort` (default size 20, sort `createdAt`) | Page of `QuestionResponse`. **Known gap:** with both category and difficulty, implementation applies category only. |
| `GET /questions/random` | required `categoryId`, `difficulty`; `limit` default 10 | JSON array of question UUIDs. Uses category AND difficulty, random order; no status filtering, version or minimum-count guarantee. `count` is not the existing parameter. |
| `POST /questions/batch` | JSON UUID array | Array of `QuestionResponse`; current `findAllById` does not guarantee request order or reject missing IDs. Consumers must map by ID and handle omissions. |
| `POST /questions` | `CreateRequest`, `X-User-Id` | 201 `QuestionResponse` |
| `PUT /questions/{id}` | `UpdateRequest`, `X-User-Id` | 200 `QuestionResponse`; options may be replaced |
| `DELETE /questions/{id}` | UUID path | 204; missing ID -> 404 |
| `POST /questions/import/csv` | multipart `file`, `categoryId`, `X-User-Id` | `ImportResult {totalRows, imported, failed, errors}` |
| `GET /subjects` | none | Subject array |
| `GET /categories` | required `subjectId` | Category array |

Subject/category controllers also support get-by-ID, POST, PUT and DELETE.

## CURRENT: DTO shape

```text
QuestionResponse:
  id: UUID
  categoryId: UUID
  categoryName: string
  content: string
  questionType: MULTIPLE_CHOICE | TRUE_FALSE | SHORT_ANSWER | ESSAY
  difficultyLevel: EASY | MEDIUM | HARD
  source: nullable string
  createdAt: local datetime (current wire value has no timezone offset)
  options: [{id: UUID, content: string, correct: boolean, displayOrder: short}]

CreateRequest:
  categoryId, content (not blank, <= 5000), questionType, difficultyLevel,
  source, options (nonempty, with content/correct/displayOrder)

UpdateRequest:
  categoryId?, content?, questionType?, difficultyLevel?, source?, options?
```

No `status`, `questionVersion`, review or structured provenance exists in this
DTO/entity. `source` is currently a free-text field, not a `QuestionSource`
entity. Correctness flags are in GET and batch responses. Current nonempty
options validation also applies to short-answer/essay create requests; owners
must decide their type-specific behavior rather than silently changing the contract.

## REQUIRED / PROPOSED: approved selection and publication input

Owner: Question Bank provider; Exam Taking consumes. Official/published exams
must never select DRAFT, PENDING_REVIEW or REJECTED questions.

Retain current route/`limit`/UUID-array shape for compatibility where possible.
Proposed extension:

```http
GET /questions/random?categoryId=<uuid>&difficulty=EASY&limit=5&status=APPROVED
```

This `status` parameter is **not implemented**. An ignored query parameter is
not approval enforcement. Before official generation is enabled, the provider
must actually filter to APPROVED; Exam Taking must explicitly request the
approved capability and independently validate every chosen question at publish
(including manual selection and changes since draft generation). Absence of
status/version data or provider errors blocks publication; never fall back to
unfiltered random questions or treat old questions as approved implicitly.

Required public data, via agreed additive DTO fields or an explicitly negotiated
compatible endpoint: stable question identity, approval state and a version
identifier that binds content, options and the grading key. Editing any of these
must change the version; revision review/reapproval belongs to Question Bank.
`questionVersion` representation must be agreed by MAIN and both owners before
implementation, and must not be confused with a JPA optimistic-lock counter.

Selection must use category AND difficulty AND approved state, return unique
eligible IDs, and honor the requested positive bounded quantity. If fewer
approved questions are available, the provider may return fewer IDs under the
existing array contract; Exam Taking must reject the deficient official exam
request with a clear error. Never silently reduce its requested composition.
Validate counts; do not substitute draft questions to fill shortages.

Resolve/check all selected IDs before publishing. Missing IDs, stale revisions,
non-approved revisions or a failed fetch block publication. Publish binds the
exact approved version: if approval changes during the fetch, retry/revalidate
or fail rather than publish unverified mixed data. See snapshot requirements in
`exam-integration.md`. No cross-service database transaction is assumed.

The current batch/get DTO contains answers. An authorized teacher/internal
snapshot projection may include them; a student-facing projection must omit
`correct` and grading keys. A new answer-bearing internal endpoint or change in
gateway authorization requires coordinator approval; no route is invented here.

### Observable provider acceptance

- GET returns the documented DTO and 404 for an unknown question.
- Combined filters work together; approved random requests exclude every other
  state, handle invalid counts and demonstrate shortage behavior.
- All selected IDs resolve to their exact approved versions at publication.
- Consumer compatibility for UUID-array selection/batch is tested, including
  out-of-order and missing batch results.
- Question changes yield a new revision; they do not mutate an already captured
  exam snapshot. Student responses never expose the grading key.

## CURRENT: Exam Builder selection (scope lock B, 2026-10-12)

Provider: `QuestionController`, `MultiBankController`, `ExamEligibilityService`,
`QuestionSetService.eligibleItems`. All routes use the same bank-aware eligible
universe as `POST /questions/batch?eligible=true` (and `GET /questions/random`):
Personal ACTIVE owned by the actor, System ACTIVE, items of PUBLISHED Public posts,
and approved Organization publications **only when `bankId` is that Organization
bank and the actor has BANK_USE** (online check, fails closed 503). Every returned
id resolves through the eligible batch; consumers still re-check on save/publish.
Filters are optional and AND-combined: `bankId`, `categoryId` (exact category,
no subtree), `questionType`, `difficulty`, `text` (trimmed, literal
case-insensitive substring, ≤200 chars; `%`/`_` are not wildcards). Unknown or
unreadable `bankId` → 404.

| Route | Caller | Request | Response |
| --- | --- | --- | --- |
| `GET /questions/eligible` | user JWT | query filters + `page`, `size` (≤100, default 20) | Spring Page of Safe question DTO (no `correct`, `expectedAnswer`, `explanation`, `sourceExcerpt`); `eligible=true`; order newest (`updatedAt`/publication time) then id. `id` is the resource id to put in the exam (Question id, Public item id or Organization publication id). |
| `POST /questions/eligible/ids` | user JWT | JSON `{bankId?, categoryId?, questionType?, difficulty?, text?}` | `{ids: UUID[], total, truncated}`; at most **500** ids in browse order; `truncated=true` when `total>500` (UI must say only 500 were selected). |
| `GET /question-sets/{id}/eligible-items` | Set owner (user JWT) | path | `{setId, setVersion, items[], skipped[]}`. `items[]`: `{position, questionId, setQuestionVersion, questionVersion, versionChanged, question(Safe DTO of the current ACTIVE version)}`. `skipped[]`: `{position, questionId, setQuestionVersion, status?, reason, message}` with `reason` ∈ `QUESTION_NOT_FOUND`, `NOT_ACCESSIBLE`, `NOT_ACTIVE` (status `DRAFT`/`ARCHIVED`). Positions are the Set's current-version order. Non-owner → 404. |
| `POST /questions/eligible/random` | exam-service (`X-Service-Token` + `X-Question-Owner-Id` = teacher) **or** user JWT | `{bankId?, categoryId?, questionType?, difficulty?, count (1..200), excludeQuestionIds? (≤1000)}` | `{ids: UUID[], requested, available}`; random order, never contains an excluded id; `available` = matching eligible count after exclusion. Shortfall = `requested - ids.length` (= `max(0, requested-available)`); provider never backfills from other filters. |
| `POST /questions/eligible/matrix-counts` | user JWT (teacher) | `{categoryIds: UUID[1..50], bankId?, questionType?}` | `[{categoryId, difficulty, available}]`: eligible counts grouped by topic and difficulty, same universe and actor scope as `/eligible/random`; empty cells are omitted. Used by the exam matrix UI before generating. |

Blueprint usage (exam-service): call `/questions/eligible/random` once per row,
passing every id already picked by earlier rows plus the request's
`excludeQuestionIds` as `excludeQuestionIds`; this guarantees no duplicate
across rows. Then freeze via `POST /questions/batch?eligible=true&includeAnswers=true`.
The service token may call only `GET /questions/random`, `POST /questions/batch`
(eligible) and `POST /questions/eligible/random`; other routes → 403.
`GET /questions/random` is unchanged for compatibility (now backed by the same query).

## FUTURE

Structured provenance, richer QuestionVersion history and QuestionReview audit
APIs can extend the provider later. No source/version/review tables or routes
are defined as currently available by this document.

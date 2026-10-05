# Question Bank -> Exam Taking integration

## CURRENT integration points

| Consumer | Current behavior | Source |
| --- | --- | --- |
| Exam Service | Feign `GET /questions/random` with category, difficulty and `limit`, repeated for requested EASY/MEDIUM/HARD counts; stores IDs | `exam-service/.../client/QuestionBankClient.java`, `service/ExamService.java` |
| Exam Session | Fetches current details through `POST /questions/batch` when a published exam starts; its local option DTO omits `correct` | `exam-session-service/.../client/QuestionBankClient.java`, `service/SessionService.java` |
| Grading | Uses `POST /questions/batch` including `correct`, both to grade and reconstruct submission detail | `grading-service/.../client/QuestionBankClient.java`, `service/GradingService.java` |

These are API/DTO calls, not database-sharing requirements. A misleading comment
in Session says Grading “queries DB”; actual Grading uses Session's Feign API
`GET /sessions/{sessionId}/internal/answers`. Maintain service DB ownership.

Current Exam routes:

```text
GET    /exams                         creator's page; X-User-Id required
GET    /exams/{id}
GET    /exams/code/{code}
POST   /exams/manual                  title, durationMinutes, questions[{questionId,weight}], settings
POST   /exams/auto                    categoryId, easyCount, mediumCount, hardCount, exam metadata
PUT    /exams/{id}/publish
DELETE /exams/{id}
GET    /exams/{id}/questions/shuffled?studentId=<uuid>  UUID array
```

Manual/auto creation require `X-User-Id`. Current publish only checks that the
question ID list is nonempty; it does not validate approval/version or capture
question text/options. Manual weights are accepted by the DTO but discarded by
`createManual`; `totalScore` currently returns null. The exam entity persists
only question IDs, not an immutable question snapshot.

Current session flow uses `POST /sessions/start?examCode=...`,
`POST /sessions/{id}/answers?questionId=...` with `{content: ...}`, and
`POST /sessions/{id}/submit` (204). Start returns session ID, exam metadata and
question DTOs. Current multiple-choice grading compares answer content to an
option UUID and assigns 1 point; this does not preserve manual weights.
See `events.md` for automatic timeout/submission notification.

Current Exam frontend (`frontend/src/features/exams/examApi.ts`) calls
`POST /exams`, `PUT /exams/{id}` and `POST /exams/{id}/publish`; those do not
match the above controller routes. It uses `OFFICIAL`/`ARCHIVED`, while backend
uses `EXAM`/`COMPLETED`. This is an Exam Taking-owned integration gap; do not
change Question Bank or the global API client to hide it.

## Status ownership

| Domain/service | CURRENT backend values | REQUIRED / PROPOSED |
| --- | --- | --- |
| Question Bank | No status enum/field | `DRAFT`, `PENDING_REVIEW`, `APPROVED`, `REJECTED`; transitions/reapproval owned by Question Bank |
| Exam Service | `DRAFT`, `PUBLISHED`, `COMPLETED`; exam type `PRACTICE`, `EXAM` | Its owner controls transitions and reconciles UI enum differences; official publication has approved-only and snapshot gates |
| Exam Session | `IN_PROGRESS`, `SUBMITTED`, `AUTO_SUBMITTED`, `CANCELLED` | Session owner controls timing, answers and submission transitions |
| Grading | `PENDING`, `GRADED` | Grading owner controls auto/manual grading and result transitions; UI-only status names are not shipped backend states |

Do not create a global Status enum shared across these lifecycles. Any added
shared wire semantics need MAIN agreement, not imports of foreign entities.

## REQUIRED: immutable published exam

Once an Exam is PUBLISHED, later Question Bank edits, review changes, option
replacement or deletion must not silently alter its questions or grading key.
Existing published exams remain reproducible; new exams validate current
approved revisions. Historical availability must survive Question Bank outage.

The Exam Taking domain owns capturing/persisting a sufficient snapshot at
publication and serving it to Session and Grading via public APIs. The provider
owns approved/versioned inputs. The minimum concept is:

```text
ExamQuestion snapshot:
  questionId
  questionVersion
  questionType
  content snapshot
  option snapshot: original option identity, text, display order
  points / weight
  grading key bound to the same version (server-side only)
```

Exact storage/API shape is left to the owners within this contract. A version
reference alone is sufficient only if the referenced immutable content/options
AND grading key are durably retained and reliably retrievable. A live mutable
question ID alone is insufficient. Do not create shared JPA entities/modules.

Session must reproduce the captured content/options and persist its chosen
question/option ordering when needed. Grading and result reconstruction must
use the same snapshot/version/points, including manual grading; they must not
fetch a newer Question Bank answer key for a published exam. Preserve option
identities that student's saved answers refer to.

Correct-answer storage follows the current separation: authorized teacher or
internal provider DTOs can carry correctness; student-facing Session DTOs omit
it. Persist exam grading data server-side and exclude correctness flags, answer
keys, rubrics and hidden scoring fields from student payloads and frontend
state. Current Question Bank routes still expose `correct` through the gateway
to authenticated callers; direct student access needs a coordinated authorization
decision, not an assumed protection. Shared gateway/auth changes belong to MAIN.

Publication must fail with an actionable error until approval, revision and
snapshot checks pass. Required behavior is not implemented in this setup.
Editing a published exam's content/points requires a separate explicit revision
or replacement exam; it cannot rewrite historical sessions in place.

## Domain requests queued for the next implementation wave

| Request | Provider/owner | Dependent step |
| --- | --- | --- |
| Approved selection plus approval/version-bearing publication input | Question Bank, shared wire semantics reviewed by MAIN | Official auto/manual publication |
| Immutable question/option/key/points capture and APIs for Session/Grading | Exam Taking | Reproducible student session and grading |
| Student-safe access policy for answer-bearing Question Bank endpoints | MAIN with both owners | Answer secrecy across browser and service access |
| Align exam frontend routes/types and retain manual weights | Exam Taking | Create/publish flow and consistent scoring |

Use the Contract Request template and Orca escalation; no domain may edit a
foreign implementation or database to get past one of these gaps.

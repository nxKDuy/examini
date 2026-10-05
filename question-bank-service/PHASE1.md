# Question Bank Phase 1

This service owns Questions, options, taxonomy, review records and immutable full
Question versions. It does not access Exam, Session, Grading or Auth databases.

## Lifecycle and ownership

Manual creation and CSV import produce `DRAFT`, version 1, `PERSONAL` Questions
owned by the signed access-token subject. Existing data is backfilled as DRAFT,
never implicitly approved. TEACHER/ADMIN owners may author their own Questions.
ADMIN may read/review PERSONAL Questions but cannot edit another owner's content.

`DRAFT -> PENDING_REVIEW -> APPROVED | REJECTED` is enforced by the backend.
Editing DRAFT, REJECTED or APPROVED increments the version and returns to DRAFT.
PENDING_REVIEW/ARCHIVED cannot be edited. Owner archive is soft and retains history.
Rejected Questions must be edited before resubmission. Reject requires a note.
Mutations require the current positive `questionVersion`; stale edits/reviews
return 409. Row locks serialize mutation and approval of the exact version.

Every creation/edit stores a full JSON snapshot, including option UUIDs and keys,
category reference/name, provenance and changedBy. Lifecycle transitions do not
rewrite snapshots; review records separately bind decision/reviewer/note/time to
the reviewed version. `questionVersion` is independent from JPA `lockVersion`.
Editing approved content removes it from eligibility until the new version passes
review. Exam consumers must retain their own immutable snapshots after publication.

## Provider HTTP contract

All routes require authenticated access, except read-only health/API documentation.

| Route | Behavior |
| --- | --- |
| GET `/questions` | Safe paginated list; categoryId, difficulty, questionType, status, creationMethod, sourceType, text; default size20, maximum100 |
| POST `/questions` | Owner manual creation, MANUAL/DRAFT/version1 |
| GET `/questions/{id}` | Current data and questionVersion; safe by default |
| PUT `/questions/{id}` | Owner edit; body includes questionVersion |
| POST `/questions/{id}/submit-review` | Owner; body `{questionVersion}` |
| POST `/questions/{id}/review` | ADMIN; body `{questionVersion,decision,reviewNote}` |
| DELETE `/questions/{id}?questionVersion=...` | Owner soft archive |
| GET `/questions/{id}/versions` | Full snapshot history, newest first; safe by default |
| GET `/questions/{id}/versions/{version}` | Specific snapshot; safe by default |
| GET `/questions/{id}/reviews` | Version-bound review history |
| POST `/questions/import/csv?categoryId=...` | Multipart file; totalRows/imported/failed/errors with CSV line numbers |
| GET `/questions/random` | categoryId, difficulty, limit(default10,1..200), optional questionType, status(default APPROVED); UUID array, shortage returns fewer |
| POST `/questions/batch` | UUID array body; eligible(default true), includeAnswers(default false); deduplicated first-occurrence order, maximum200 distinct IDs / 1000 raw elements |

Random always selects accessible PERSONAL APPROVED Questions; requesting another
status returns 400. Eligible batch is all-or-error: missing/foreign IDs return the
same 404; accessible nonapproved/archived IDs return 409. Batch returns frozen
current-version content/options with current lifecycle context. Repeatable-read
transactions preserve a consistent read across selection/snapshot loading.
`eligible=false` is restricted to signed authoring users with normal read access;
the service credential cannot bypass eligibility.

Question DTO identity fields are `id` and `questionVersion`; consumers map `id`
to their snapshot's `questionId`. Common fields include content, questionType,
difficultyLevel, categoryId/name, options(id/content/displayOrder), scope/status,
createdBy/timestamps and provenance. Safe DTOs physically lack `correct`,
`expectedAnswer` and `sourceExcerpt`. List is always safe. Detail, versions and
batch accept explicit `includeAnswers=true` only for TEACHER owner, ADMIN, or
the eligible internal batch lane. STUDENT never receives keys, even with the same
owner UUID. Author mutations return full author-only data. Safe data cannot stop
authors embedding answers in visible question text/labels; content review remains
necessary.

MULTIPLE_CHOICE and TRUE_FALSE keys use option UUIDs; TRUE_FALSE has exactly two
options with one correct. SHORT_ANSWER requires an expectedAnswer; ESSAY's key is
optional. The provider preserves expectedAnswer verbatim. The MAIN-approved
consumer normalization is trim, Unicode whitespace collapse and NFC, case-sensitive;
no fuzzy grading or mandatory fabricated essay key is supplied here.

## Authentication and deployment boundary

`JWT_SECRET` uses Auth's UTF-8 HMAC signing key. Signature, expiry, access type,
UUID subject and recognized role are checked locally. Spoofable X-User-* headers
are ignored. ADMIN is the existing privileged reviewer role; no reviewer teams.
Role issuance belongs to Auth: current registration accepts a requested role, so
MAIN/Auth must restrict privileged role issuance before production review rollout.
MAIN confirmed that public registration can self-assign ADMIN: deployment is
blocked until assignment is fixed and existing self-claimed privileged accounts
and tokens are reviewed, revoked or normalized. A code fix alone does not repair
previously issued roles.

`QB_INTERNAL_TOKEN` is a separate server credential, empty/absent disables the
lane. Only GET random and POST eligible batch accept `X-Service-Token`, together
with UUID `X-Question-Owner-Id`; Bearer/service credentials cannot be mixed.
Token comparison is constant-time; duplicate identity/auth headers are rejected.
Consumers derive owner from persisted Exam creator, not arbitrary student input.
This credential trusts authorized server processes to assert that owner; it is
not an Organization membership or end-user authorization mechanism. Runtime
credential distribution and consumer rollout belong to MAIN and Exam Taking.

This intentionally changes legacy anonymous/header-only access and unreviewed
selection semantics. MAIN approved the Phase 1 delta; shared contract documents
must be updated/integrated by MAIN before coordinated rollout. No gateway, Auth,
root dependency, shared infrastructure or consumer files are changed here.

## Provenance and future extension

CreationMethod separates MANUAL/IMPORT from future AI_GENERATED/COPIED. SourceType
separates NONE, USER_DOCUMENT, EXTERNAL_WEB, ORGANIZATION_BANK, PUBLIC_BANK,
SYSTEM_BANK. Optional sourceRefId/label/url/excerpt are opaque metadata; legacy
source is retained and backfilled into sourceLabel. No ingestion or URL fetching.
CSV import selects IMPORT server-side, validates all types/options/keys and never
approves. Bounds: 10MB/1000 rows, 26 options, strict true/false option flags.

ORGANIZATION and SYSTEM are enum extension points only; API creation rejects
inactive scopes. Future membership/curation policies extend the central policy,
without changing Question lifecycle. No fake organization data is created.
Future PublicQuestion should be a separate verified representation referencing
`sourceQuestionId` and `sourceQuestionVersion`, with independent verification and
stable public content. Original ownership remains intact and community data later
attaches to publicQuestionId. PUBLIC is not a mutable Question scope.
No Organization management, PublicQuestion, social features, crawl, AI generation,
quality scores, recommendation or usage analytics are implemented.

## Verification

40 backend tests cover validation/lifecycle, exclusions, versions, provenance,
CSV, signed identity and service boundaries, safe JSON, strict batch ordering,
legacy Flyway backfill, actual PostgreSQL queries and concurrent stale edits.
Persistence tests require `QB_TEST_DATABASE=true` and an isolated configured
PostgreSQL database; without that flag they are intentionally skipped. Run
`mvn -pl question-bank-service -am verify` with that test-only environment.
Scoped frontend TypeScript/oxlint and isolated browser create/edit/review/version/
CSV smoke tests pass. Full frontend build still has foreign baseline errors;
the final handoff report records those rather than changing sibling domains.

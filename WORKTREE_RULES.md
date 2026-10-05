# EXAMINI worktree rules

This is the authoritative ownership and coordination document. Read this and
`contracts/**` before changing the repository. MAIN coordinates; the two domain
workers implement their own business features only when assigned a separate task.

## Discovered topology

All three checkouts started at `2b07f154a9b27aaa6c4d5c5c171799c357766f82`.
Use the existing branches; do not create duplicate `feat/*` branches.

| Role | Branch | Checkout | Orca worktree ID |
| --- | --- | --- | --- |
| MAIN / coordinator | `main` | `/Users/mac/DUY/DATN` | `34f16bc0-321f-4a7a-9d1a-53b1710f74e5::/Users/mac/DUY/DATN` |
| Question Bank | `feature/question-bank-domain` | `/Users/mac/orca/workspaces/DATN/wrasse` | `34f16bc0-321f-4a7a-9d1a-53b1710f74e5::/Users/mac/orca/workspaces/DATN/wrasse` |
| Exam Taking | `feature/exam-taking-domain` | `/Users/mac/orca/workspaces/DATN/exam-taking-domain` | `34f16bc0-321f-4a7a-9d1a-53b1710f74e5::/Users/mac/orca/workspaces/DATN/exam-taking-domain` |

Orca display name `question-bank-domain` maps to the directory `wrasse`.
Runtime terminal/Dispatch IDs can change; discover them through Orca rather than
inferring them from these branch names. See `contracts/README.md`.

## Ownership

| Owner | Primary paths | Business responsibility |
| --- | --- | --- |
| MAIN / SHARED | `contracts/**`, `WORKTREE_RULES.md`, `AGENTS.md`, repository integration/architecture documentation and protected configuration below | Cross-domain contracts, repository rules, architecture decisions, genuinely shared configuration, cross-domain conflict resolution. Do not implement Question Bank or Exam Taking feature logic here. |
| Question Bank | `question-bank-service/**`; migrations under Question Bank | Question, QuestionType, Difficulty, QuestionStatus, QuestionSource, QuestionVersion, QuestionReview, approval workflow, source provenance, import and filtering; provider APIs for other domains. Do not edit Exam Taking internals. |
| Exam Taking | `exam-service/**`, `exam-session-service/**`, `grading-service/**`; migrations under those services | Exam, ExamQuestion, ExamSession, Answer, Submission, GradingResult, timer, submit, auto/manual grading and results. Consume Question Bank public APIs; do not change its entities, persistence or lifecycle. |
| Frontend App (`frontend/app`) | `frontend/**` | Phase 1 frontend product development, application UI, shared frontend runtime/configuration, API integration and UI validation. Backend implementation is read-only; request missing contracts from their provider and MAIN. |

For ongoing Phase 1 UI work, Frontend App owns all frontend files, including
Question Bank, Exam and Grading screens. Backend domain owners retain business
semantics and public contracts; they do not independently edit frontend files.
Consumers use public DTOs without importing foreign persistence entities.
Frontend guards are UX only: backend authorization is authoritative; TEACHER
persona never implies ADMIN. Reuse the shared API/auth runtime and never
manufacture identity headers.

Frontend UI work stops at `READY FOR MANUAL UI VALIDATION` after the relevant
TypeScript/build/tests and browser checks. Syncing from MAIN and merging/pushing
require an explicit task instruction; never do them automatically. Visual skills
must suit application dashboards rather than impose landing-page aesthetics.

## Shared and protected paths

MAIN coordinates changes to:

- Root `pom.xml`, `docker-compose.yml`, `.env.example`, `.gitignore`,
  `docker/**`, root startup/build scripts and shared infrastructure configuration.
- `api-gateway/**`, `eureka-server/**`, `auth-service/**`, `analytics-service/**`.
- Repository-wide rules, architecture/integration docs and `contracts/**`.

Frontend App coordinates frontend-only shared files such as package/config,
router, API/auth runtime, components and global styles. Cross-domain contract,
authorization semantics, backend or repository infrastructure changes still
require MAIN approval. Keep frontend shared changes minimal and in dedicated
commits, and avoid unrelated dependency upgrades.

Service-local POMs/config belong to that service owner; changes to shared
dependency semantics or infrastructure still need a coordinator decision.
Unclassified paths require an ownership decision before editing.

Avoid shared edits unless required. For MAIN-owned shared paths, request
coordinator approval with the path, reason and minimal proposed change. Shared
edits must be isolated from feature work in dedicated commits with their reason.
No drive-by refactoring, unrelated dependency upgrades, global formatting or
moving code across domains for aesthetic cleanup. No secrets in commits/mail.

## Database and contract boundaries

NO domain/service may directly query or mutate another service's database
tables, including services within the Exam Taking worktree. For example:

```text
Forbidden: Exam Service -> SELECT from Question Bank tables
Required:  Exam Service -> Question Bank public API -> consumer DTO
```

Do not share JPA entities and do not create a common `QuestionEntity` module.
Use public DTO/API/event contracts only. A missing API is a Contract Request,
never permission to edit a foreign domain or open its database.

Question lifecycle is owned by Question Bank. Exam, Session and Grading each own
their lifecycle. Do not introduce one global `Status` enum. See the current and
required statuses in `contracts/exam-integration.md`.

Official (`EXAM` in current backend) publication requires APPROVED questions
only, and a published exam must remain immutable against later Question Bank
edits. These are required invariants, not guarantees of the current code.

## Communication and blockers

Load the installed guide before changing Orca orchestration state:

```sh
orca skills get orchestration --full
```

Use live preamble IDs/commands; never reconstruct lifecycle authority from an
old document. The current setup uses Orca 1.4.220 and supervised `worker-start`
on the existing agent terminals. Do not use retired coordinator scheduler verbs.

1. **Direct information:** status, contract implementation ready, commit SHA,
   test evidence and non-breaking changes may go to the sibling's current
   `dispatch:<id>`. Example: “Question contract implemented. Commit: abc123.
   No breaking API changes.” MAIN need not approve informational messages.
2. **Contract Request:** send `contracts/CONTRACT_REQUEST_TEMPLATE.md` contents
   to the provider's active Dispatch and copy the coordinator Run. The provider
   implements its own side. Do not edit foreign files to bypass the request.
3. **Coordinator decision:** breaking APIs, shared DTO semantics, database
   ownership, shared infrastructure, root dependencies, gateway changes affecting
   multiple domains, cross-domain schema redesign and ownership disputes need
   MAIN approval. Use the live preamble's `ask` for a blocking decision; MAIN
   responds with `reply` in Orca.

For a foreign-domain blocker: send an Orca escalation to MAIN with the Contract
Request and dependent acceptance step; notify the provider; continue independent
work and wait only for the dependent portion. If the provider is not dispatched,
send the request to MAIN for routing rather than addressing a settled Dispatch.
Orca is the transport; humans should not copy/paste terminal messages.

Workers check their inbox before a new file, after tests and before completion.
Copy the exact `check --terminal`, `send --from`, Task and Dispatch arguments
from their injected preamble. An enqueue receipt alone is not a read receipt.
For pending blocking questions resume the same message ID instead of creating
duplicates. MAIN processes the whole FIFO delivery before acknowledging it.

At meaningful checkpoints, read the existing Orca worktree comment first and
preserve useful user context before updating the short progress line. Example:
“approval workflow implemented; integrating approved-only query” or “session
E2E implemented; blocked on approved question contract”. Use workspace status
`in-progress`, `in-review` or `completed` only for what is actually complete.
Setup completion does not mean the domain MVP is completed.

Before reporting business completion, run relevant builds/tests and name the
commands/results, domain commits, every modified shared file, unresolved
dependencies and the integration checklist evidence. Follow Orca's exact
`worker_done` preamble; send one explicit succeeded/failed outcome and idle.

## Git synchronization

`main` is the local shared integration baseline. `origin/main` is the remote
baseline and does not receive these local changes until an explicitly authorized
push. The domain branches are currently private, have no upstream and initially
contain no domain commits, so the initial setup is distributed by clean
`git merge --ff-only main` in each existing domain checkout.

After domain work begins:

1. Keep domain commits isolated. Do not repeatedly merge sibling branches.
2. Bring reviewed rules/contracts/shared fixes to MAIN as dedicated commits,
   independently of unfinished business features. MAIN may cherry-pick an
   approved shared-only commit after reviewing its complete diff; not a mixed
   domain feature commit.
3. Commit or safely save local work, inspect `git status` and agree on the new
   baseline. A private, unpublished domain branch can `git rebase main`.
4. If a branch is published or otherwise shared, do not rewrite its history;
   use a reviewed `git merge main` instead. Never force-push just to sync rules.
5. Integrate business work only after owner review and relevant acceptance
   checks; do not merge unfinished domain branches to simplify setup.

Each worker mutates Git only in its own checkout; MAIN arranges the initial
fast-forward before dispatch while both workers are idle. No automatic sibling
merges, resets, stashes or conflict overwrites. Keep feature and contract commits
separate. Resolve contract semantics manually before resuming integration.

MAIN contained uncommitted runtime fixes from an earlier “run project” task when
this setup began. They remain outside the shared setup commit; they are not a
new shared baseline or evidence that the feature branches contain those fixes.
Review and transfer them separately with the appropriate owners when needed.

## Conflict ownership

| Conflict | Decision owner |
| --- | --- |
| Question Bank-owned file | Question Bank owner |
| Exam-owned file | Exam Taking owner |
| Shared file | MAIN reviews both changes |
| `contracts/**` | Stop integration and reconcile semantics with both owners first |

Never use automatic `ours`/`theirs` resolution for contracts. A conflict owner
decides the intended behavior; ownership does not authorize cross-domain edits.

# Shared contracts

Markdown matches the repository's existing README convention; controllers have
Springdoc annotations but no checked-in OpenAPI contract. These documents are
the coordination baseline, not newly implemented endpoints.

| Document | Purpose |
| --- | --- |
| [Lifecycle và eligibility mới](question-bank-lifecycle.md) | Quyết định bank-aware thay APPROVED-only toàn cục |
| [Question Bank API](question-bank-api.md) | Actual provider routes/DTOs and required approved selection/version capabilities |
| [Exam integration](exam-integration.md) | Consumers, lifecycle ownership, immutable publication and security boundaries |
| [Events](events.md) | Current submission message and proposed reliability requirements |
| [Contract Request template](CONTRACT_REQUEST_TEMPLATE.md) | Foreign-domain capability and blocker requests |
| [Integration acceptance](integration-checklist.md) | Expected MVP path and reproducibility/security checks |
| [Notification / Reporting decisions](notification-reporting-decisions.md) | MAIN-approved event transport, permission scope and consumer review gates |

Labels are explicit: **CURRENT** means inspected code, **REQUIRED / PROPOSED**
means an agreed setup requirement still awaiting domain implementation, and
**FUTURE** is optional, not part of this setup. A proposed route/DTO must not be
reported as shipped without provider and consumer evidence. Root
[`WORKTREE_RULES.md`](../WORKTREE_RULES.md) owns all worktree rules.

## Orca coordination

The initial onboarding wave creates one Run with two tasks titled “Question
Bank Domain” and “Exam Taking Domain”. Their scope is reading boundaries and a
communication smoke test only; a succeeded task is onboarding success, not MVP
feature completion. Later business work uses new self-contained Tasks in the
same Run and fresh Dispatch authority, reusing the existing terminals when idle.

Runtime mapping/receipts are kept locally in the Git common directory at
`examini-coordination.json` (discover its parent with
`git rev-parse --path-format=absolute --git-common-dir`). That audit snapshot is
not live authority. After a restart re-discover the current terminals/Dispatches.
MAIN uses `orca orchestration run-current --json` / `run-list --json`,
`task-list --run <run_id> --json` and `worker-list --run <run_id> --json`.
Workers use their injected preamble; never take over another coordinator's Run
or create a duplicate Run just because a terminal is idle.

Within a live Dispatch, copy the exact sender/lifecycle flags from its preamble.
Typical informational exchange (placeholders are not literal IDs):

```sh
orca orchestration send --to dispatch:<provider_dispatch_id> --subject 'CONTRACT REQUEST: approved selection' --body '<filled template>' --json
orca orchestration send --to run:<run_id> --type escalation --subject 'Blocked: approved selection' --body '<filled template and dependent step>' --json
```

A worker uses `--from` exactly as its preamble directs; MAIN normally omits it.
For coordinator decisions use the preamble's blocking `ask`; MAIN replies with
`orca orchestration reply --id <message_id> --body '<decision>' --json`.
Keep the same request/thread IDs for follow-ups. Provider ready messages include
the commit SHA, real route/DTO, compatibility statement and relevant test results.
Contract approval, provider implementation and consumer integration are three
separate facts.

## Contract change process

Submit the request to the provider and MAIN. MAIN reconciles shared semantics;
the provider and consumer edit their own implementations. MAIN reviews a small
contract-only commit, then both domains sync from `main` according to the rules.
No direct foreign database access or shared persistence entities.

Current blocking gaps are recorded in the API/integration docs: approval and
version data are absent; published exams store only mutable question IDs; point
weights are discarded; the exam frontend uses routes/enums different from the
controller. This setup documents those gaps without implementing them.

## Authentication model and authorization boundary

Auth is the source of persisted identity and signed token claims. The current
registration contract allows personal `STUDENT` and `TEACHER` personas; public
registration cannot assign system privileges. `persona` describes the account's
personal role, while `systemPrivilege` is the platform-level privilege. The
legacy `role` claim remains for compatibility and must stay consistent with
those persisted fields; it is not a substitute for resource authorization.

`TEACHER` persona alone never means system administrator and never grants
management rights over another user's resources. Question Bank authorization
must combine the authenticated identity with question ownership and explicit
review authority. Exam authorization must use ownership and `OWNER`,
`MANAGER`, or `PROCTOR` membership as appropriate. Frontend route guards and
navigation are presentation controls only; every API must enforce its own
authorization rule. These rules apply to Question Bank, Exam Taking and
Frontend Integration.

Services must derive identity from a validated access JWT or an equivalent
trusted authentication context. Client-supplied `X-User-Id`, `X-User-Role`,
`X-User-Persona`, and `X-System-Privilege` headers are not trusted. The current
Gateway overwrites identity and role from a verified token but does not yet
propagate persona/system privilege or reject refresh tokens on protected routes;
do not make a service depend on those forwarded claims until MAIN closes that
Gateway integration follow-up.

## Auth merge follow-ups

| Owner | Follow-up | Gate |
| --- | --- | --- |
| MAIN/shared | Replace the repository's known default seeded ADMIN credential with a configured secure bootstrap; do not commit deployment secrets | Production release blocker |
| MAIN/shared | Strip/overwrite every inbound identity header and accept only access tokens at protected Gateway routes before forwarding trusted identity claims | Integration blocker; production release blocker |
| FRONTEND-INTEGRATION | Sync from the Auth-updated MAIN, then model `persona` and `systemPrivilege` in auth state, route guards, navigation and API client | Next planned task |
| Question Bank | Keep teacher authoring scoped to owned content; review authority must be explicit and independent of teacher persona | Before production integration |
| Exam Taking | Keep sensitive exam operations scoped to owner/collaborator membership; do not use teacher persona as global management permission | Before production integration |

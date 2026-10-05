# Shared contracts

Markdown matches the repository's existing README convention; controllers have
Springdoc annotations but no checked-in OpenAPI contract. These documents are
the coordination baseline, not newly implemented endpoints.

| Document | Purpose |
| --- | --- |
| [Question Bank API](question-bank-api.md) | Actual provider routes/DTOs and required approved selection/version capabilities |
| [Exam integration](exam-integration.md) | Consumers, lifecycle ownership, immutable publication and security boundaries |
| [Events](events.md) | Current submission message and proposed reliability requirements |
| [Contract Request template](CONTRACT_REQUEST_TEMPLATE.md) | Foreign-domain capability and blocker requests |
| [Integration acceptance](integration-checklist.md) | Expected MVP path and reproducibility/security checks |

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

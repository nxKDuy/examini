# CONTRACT REQUEST

Copy the filled body into Orca `send`/`ask`, not between human terminals.

```text
Request ID:
Requester:
Provider domain:

Need:
Reason:

Current contract / source:
Proposed request:
Proposed response:
Compatibility / rollout:

Breaking change: yes/no
Blocking: yes/no
Dependent step blocked:
Independent work that can continue:

Acceptance evidence required:
Shared paths / coordinator decision needed:
Provider commit / tests when ready:
```

Example (required capability, not a shipped API):

```text
Request ID: QB-APPROVED-SELECTION
Requester: Exam Taking
Provider domain: Question Bank
Need: Random questions filtered to APPROVED, with approved/versioned inputs for publish.
Reason: Published official exams must never use draft/rejected questions.
Current contract / source: GET /questions/random uses categoryId, difficulty, limit; UUID[] response; no status/version.
Proposed request: Add status=APPROVED while retaining categoryId/difficulty/limit.
Proposed response: Keep UUID[]; agree additive approval/version metadata on detail/batch with MAIN.
Compatibility / rollout: Explicit capability verification; no unfiltered fallback before provider readiness.
Breaking change: no for selection shape; version/security semantics require MAIN review
Blocking: yes
Dependent step blocked: official generation/publication
Independent work that can continue: session/timer/answer logic and snapshot work using explicit test fixtures
Acceptance evidence required: exclude DRAFT/PENDING_REVIEW/REJECTED; enforce shortages and approved revisions at publish
Shared paths / coordinator decision needed: contracts/** and any gateway access-policy changes
Provider commit / tests when ready: pending implementation task
```

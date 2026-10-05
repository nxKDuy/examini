# Cross-domain integration acceptance

This is the eventual MVP acceptance path. Setup creates the expectations; the
checkboxes below are not implementation claims. Record real commit IDs, build/
test commands and results when each step passes.

```text
Create Question -> Submit Review -> Approve Question -> Create Exam
-> Generate/select approved questions -> Publish Exam -> Start Student Session
-> Answer -> Submit -> Grade -> Result
```

- [ ] Question Bank creates a question with source/provenance appropriate to its contract.
- [ ] Submit review and approve through Question Bank-owned transitions; draft/pending/rejected revisions remain ineligible.
- [ ] Exam Taking manual selection and random generation use real public provider DTO/API calls, never foreign tables.
- [ ] Combined category/difficulty/APPROVED selection works; insufficient eligible questions produces a clear failure.
- [ ] Publish revalidates every selected approved revision and captures content, option identities/order, grading key and points.
- [ ] Student starts a published exam, receives the immutable student-safe snapshot and saves answers only for that session/exam.
- [ ] Timer is server-authoritative; timeout and manual submit converge without duplicate submissions/results.
- [ ] Grading consumes the submitted-session notification and uses snapshot answer keys/weights; transient failures are observable/recoverable.
- [ ] Auto/manual scoring and result/submission detail reproduce the same version; student payloads never reveal the grading key.
- [ ] Frontend routes, request bodies and enums match actual Exam/Session/Grading controllers.

## Mandatory immutability regression

- [ ] Publish an approved exam and record the snapshot/version, content, option IDs/order and points.
- [ ] Edit Question Bank question text/options/correct answer and move the new revision through review; also test deletion/unavailability of live data.
- [ ] Start/reload a session for the already-published exam: content/options/points are unchanged.
- [ ] Submit the original answer: grading and result detail use the original captured key/version, not the later Question Bank revision.
- [ ] A new exam must validate the new/current approved revision; historical preservation does not grant eligibility to an unapproved revision.

## Integration review evidence

- [ ] Each owner reports domain-focused commits, relevant backend/frontend builds/tests and unresolved dependencies.
- [ ] All shared modifications are explicitly named, approved and committed separately.
- [ ] No cross-service DB access, foreign JPA entity imports or shared global Status enum was introduced.
- [ ] MAIN reconciles contracts before merging finished domain work; no unresolved contract conflict remains.
- [ ] Runtime smoke test goes through the gateway/frontend and covers roles, authorization and the full path above.

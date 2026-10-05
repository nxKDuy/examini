# Phase 1 runtime / release gate — 2026-10-05

Decision: **PHASE 1 RELEASE GATE PASS WITH NON-BLOCKING FOLLOW-UPS**.
Validated the integrated MAIN working tree starting at `4ead71613b74df2621ea98831d7e111f501ec2df`. No Phase 2 features or new domain functionality were added.

## Environment and isolation

- Docker Desktop client/server 29.8.1; Compose v5.5.1.
- Java Eclipse Temurin 21.0.12.1 (containers); Maven 3.9.16 for service tests, build image Maven 3.9.6.
- Host Node v22.23.3 / npm 10.9.9; browser CLI agent-browser 0.27.0.
- PostgreSQL 16.15, Redis 7.4.11, RabbitMQ 3.13.7.
- Host Java/Maven are unavailable; container tools executed the real backend builds/tests.
- Compose project `examini-phase1-runtime` started fresh DB/cache/broker volumes. Existing Question Bank worker containers were left running.
- Host IPv4 port 8080 belongs to another runtime. A local untracked Compose override maps the validation Gateway to `127.0.0.1:18080`; real Vite frontend runs at `127.0.0.1:13000`, with `GATEWAY_URL=http://127.0.0.1:18080`.
- Initial BuildKit Maven DNS failure was transient; a retry built all seven application images successfully.
- Local ignored `.env` contains configured secrets, mode 0600. No secret values are committed or included here.

## Backend and migration results

Service tests executed with Maven in an isolated container sharing a test PostgreSQL network namespace:

```sh
mvn -f auth-service/pom.xml test -q
mvn -f question-bank-service/pom.xml test -q
mvn -f exam-service/pom.xml test -q
mvn -f exam-session-service/pom.xml test -q
mvn -f grading-service/pom.xml test -q
mvn -f api-gateway/pom.xml test -q
```

| Service | Tests | Failures/errors/skips | Real runtime migrations |
|---|---:|---|---|
| Auth | 19 | 0/0/0 | V1–V4 PASS |
| Question Bank | 40 | 0/0/0 | V1–V2 PASS |
| Exam | 3 | 0/0/0 | V1–V3 PASS |
| Exam Session | 4 | 0/0/0 | V1–V2 PASS |
| Grading | 2 | 0/0/0 | V1–V2 PASS |
| Gateway | 8 | 0/0/0 | n/a |

Flyway histories were checked in PostgreSQL, all entries successful. Exam V2/V3 ordering and constraints ran against fresh PostgreSQL; no within-service version collision.
Eureka, Gateway, Auth, Question Bank, Exam, Session, Grading, PostgreSQL, Redis and RabbitMQ started. Grading returns actuator health UP (Compose does not define its own healthcheck). Other configured healthchecks are healthy. Analytics is not required for this core path and was not started.

## Real Gateway/API validation

- Student and Teacher public registration/login/me: correct persona, systemPrivilege NONE. Configured Admin login/me: systemPrivilege ADMIN. Teacher is not Admin.
- ADMIN and SYSTEM_ADMIN registration rejected. Unknown privileged request fields do not become authority. Spoofed identity/persona/privilege headers do not grant access. Refresh token on a protected API is rejected.
- Legacy seed Admin login rejected after Auth V4.
- Question create DRAFT, submit PENDING_REVIEW, Admin approve APPROVED; Teacher cannot review; unrelated Teacher/Student cannot read another owner's personal question. Versions/review audit available.
- Random/batch only select approved questions and return version-bearing safe DTOs without correct/expectedAnswer/gradingKey/rubric. A student's exam/session payload is also safe.
- DRAFT/PENDING_REVIEW/REJECTED Publish fails with domain rejection. APPROVED Publish and real automatic generation via provider random/batch succeed.
- Creator is OWNER. Accepted MANAGER publishes/opens/closes. PROCTOR and unrelated Teacher cannot perform lifecycle operations. PROCTOR cannot access management grading details.
- PUBLIC join/lobby/preflight works; maxParticipants=2 rejects a third participant. Two concurrent starts with maxConcurrentSessions=1 produce exactly one winner.
- Answer save/read and same-session Start resume work. Server expiry stays fixed through reload, allowing only PostgreSQL sub-microsecond timestamp rounding. Client-supplied future expiry is ignored.
- Closing Exam rejects new sessions while active session can save/finish. Duplicate submit produces one logical result; answer changes after submit fail.
- Real RabbitMQ/outbox produces real Grading/Result: correct MC/TRUE_FALSE/SHORT_ANSWER score 6/6; wrong MC/TF score zero. SHORT_ANSWER comparison normalizes whitespace/Unicode and is case-sensitive.
- Editing live question text/version after Publish leaves student content/version unchanged. Changing live answer key leaves frozen grading correct. Teacher submission detail reconstructs original snapshot content.
- Missing external essay AI yields NEEDS_REVIEW with no published score. One-minute timeout becomes AUTO_SUBMITTED and is graded through RabbitMQ; late answer mutation fails.

## Real browser validation

No fixture server or synthetic JWT was used for these browser checks.

- Teacher: real login, Question Bank displays real lifecycle data, UI creates an Exam using an approved provider question, saves PUBLIC policy, Publish/Open succeeds.
- Student: real login, Student navigation, PUBLIC discover/join, Lobby, Start, select answer, browser refresh preserves answer/session/server timer, confirm Submit, real GRADED result 1/1.
- Admin: real bootstrap login and Admin navigation. Runtime exposed and fixed a frontend adapter rejecting canonical `persona: null`; regression tests now cover Admin null persona and reject personal null persona.

## Admin bootstrap / preservation

Auth V4 disables the repository-known seed hash for all seeded accounts while retaining IDs/resources. Existing migration files were not edited. Bootstrap accepts environment-provided Admin credentials, rejects weak/partial configuration and refuses promotion of an existing personal account. A fresh `prod` profile requires configured credentials or an existing non-disabled Admin. Remove bootstrap configuration after setup to avoid rewriting its configured password on each restart.

Original pre-existing Redis/RabbitMQ Compose and Session/Grading Actuator changes are runtime-required and recorded in a dedicated runtime commit. Original Analytics POM changes remain uncommitted and untouched. Original stash `18469bd6c2ff228aa6ceb22c9d01c5c9a4545df3` remains intact. Nothing discarded. No worker worktree changed and nothing pushed.

## Frontend gates

```sh
cd frontend
npm exec -- tsc -b --pretty false  # PASS
npm run build                     # PASS
bash tests/validate-shared.sh      # PASS: 20 auth runtime tests, compile/lint/bundle
```

## Reproduction and evidence

```sh
PHASE1_GATEWAY_URL=http://127.0.0.1:18080 python3 scripts/phase1_runtime_e2e.py
```

Use an isolated development stack: the script creates data and waits for a real one-minute timeout. It stores local credentials/tokens in a restricted `/tmp` account artifact for optional real browser validation. Do not publish that artifact.

Non-secret results/logs/screenshots are in `/tmp/examini-phase1-runtime-validation/`; browser screenshots cover Teacher, Student Result and Admin. Backend Surefire reports also remain under each module's ignored `target/surefire-reports/`.

## Non-blocking follow-ups

| Owner | Severity | Follow-up / impact |
|---|---|---|
| MAIN / Analytics + Grading | Medium | Analytics calls `/grades/exam/{examId}/stats` without a matching controller. Analytics statistics remain unavailable; core exam path does not require them. |
| FRONTEND-INTEGRATION | Low | `NotificationBoard.tsx` has sample notifications but is not imported into current production routes. Keep it unmounted until real data is available. No mock Question/Exam/Result used in validated production flow. |
| MAIN / operations | Low | Remove bootstrap credential configuration after Admin setup, deploy with proper secret management. No known default Admin credential remains usable. |

Capacity tests establish small correctness boundaries, not production load capacity. External AI essay scoring was unavailable; manual-review fallback passed. Next action: begin next Phase planning; Phase 2 has not started.

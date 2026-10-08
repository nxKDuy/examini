# EXAMINI frontend workspace

- `frontend/app` owns `frontend/**`. Read root `WORKTREE_RULES.md` and
  `contracts/**`; they are the authoritative ownership and API contracts.
- Backend implementation is read-only. Report missing/wrong backend contracts
  as Contract Requests to their owner and MAIN.
- TEACHER != ADMIN: persona controls persona UX; systemPrivilege controls
  global Admin UX. Backend authorization is authoritative; handle 401/403.
- Reuse `src/lib/api.ts`, `src/store/authStore.ts` and shared auth runtime.
  Never manufacture identity headers or introduce a second token/auth flow.
- Before completion run `npm exec -- tsc -b --pretty false`, `npm run build`,
  existing relevant tests/validation and `git diff --check`; report results.
- Completed product UI work stops at `READY FOR MANUAL UI VALIDATION`.
- Never merge MAIN or push automatically; require an explicit task instruction.
- Use skills for procedural workflows. Adapt visual judgment to application
  dashboards; do not blindly apply landing-page aesthetics.

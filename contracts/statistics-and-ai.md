# Statistics and AI readings (additive, 2026-10-30)

All aggregates; no route below returns another person's identity. AI is optional everywhere: with no model configured
(`GEMINI_API_KEY` / `OLLAMA_URL`) the rule-based text is returned and `aiAvailable=false`. Models only receive the
aggregates shown in the response, never question text, answers or names.

## Teacher (analytics-service, exam OWNER/MANAGER as for other exam analytics)

- `GET /analytics/exams/{examId}/questions` — each item gains `discrimination` (upper−lower 27 % index, −1..1,
  null under 10 GRADED attempts).
- `GET /analytics/exams/{examId}/insights?ai=false&lang=vi` →
  `{ examId, source: RULES|AI, model, level: GOOD|FAIR|AVERAGE|WEAK|UNKNOWN, summary, strengths[], concerns[], actions[],
     flags: [{ position, questionType, issue: TOO_HARD|TOO_EASY|OFTEN_SKIPPED|NEGATIVE_DISCRIMINATION|LOW_DISCRIMINATION, detail }],
     aiAvailable, generatedAt }`. `ai=true` asks the model (cached 30 min per data state); any model failure returns RULES.

## Student (grading-service, the signed-in user only)

- `GET /grades/my` → `{ overview: { exams, attempts, published, averagePercent, bestPercent, latestPercent, trend, byType[] }, items[] }`.
  Scores appear only for published results whose exam policy shows the score; `byType` only when the breakdown is shown.
  Invalidated attempts are listed but never averaged. `trend` = mean of the latest ≤3 scored results − the ≤3 before.
- `GET /grades/my/insights?ai=false&lang=vi` → `{ source, summary, tips: [{ kind: STRENGTH|FOCUS|HABIT, text }], aiAvailable }`.

## Administrator (systemPrivilege ADMIN; 403 otherwise; `days` 1..365, default 30)

| Route | Owner | Content |
|---|---|---|
| `GET /auth/admin/stats` | auth | users by role, locked, Google-linked, active 7/30 days, new per day |
| `GET /exams/admin/stats?ids=` | exam | exams by status/type, camera-enabled, created per day; titles for ≤50 requested ids |
| `GET /questions/admin/stats` | question-bank | questions by status/type/difficulty/origin, with media, public posts, import jobs, AI usage (runs, failures, drafts, kept, by model), created per day |
| `GET /analytics/admin/overview` | analytics | attempts/submissions per day, graded, waiting, active exams/students, invalidated, published average and distribution, top exams |

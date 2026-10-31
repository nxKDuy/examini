# Practice feedback (additive, 2026-10-31)

Owner: exam-session-service. Consumers: frontend attempt page.

- A session started for an exam of type `PRACTICE` is a practice attempt (`exam_sessions.practice`, migration V8).
  `GET /sessions/{id}` and `POST /sessions/start` responses add `practice: boolean` and
  `checks: { questionId → PracticeCheck }` (only answers already checked).
- `POST /sessions/{id}/answers/check?questionId=…` (owner, attempt in progress):
  - 409 when the attempt is not a practice attempt, or the question is ESSAY/SPEAKING (graded by a teacher);
  - 400 when the question has no saved answer yet;
  - otherwise the verdict `PracticeCheck { questionId, share (0..1), correct, correctAnswers[], correctOptionIds[],
    parts { optionId → right }, explanation }`, computed with the same rules as grading-service (THPT scale for
    TRUE_FALSE_GROUP, share per gap/pair for FILL_BLANK/MATCHING). The key comes from exam-service
    `GET /exams/{id}/internal/grading`.
- A checked answer is locked: `POST /sessions/{id}/answers` for it returns 409; checking again returns the stored verdict.
  Submission and grading are unchanged; official exams (`EXAM`) never reveal keys during the attempt.

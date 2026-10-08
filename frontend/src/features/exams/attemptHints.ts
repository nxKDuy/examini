// Remembers which session this browser last started for an exam, so the lobby and
// dashboard can offer Resume / Result. It is only a hint: every use is re-validated
// with GET /sessions/{id}, and the server's Start call resumes an active attempt anyway.
// Backend gap: there is no student endpoint listing own sessions (reported as a contract request).

const KEY = 'examini-attempts'
type Hints = Record<string, Record<string, string>> // userId -> examId -> sessionId

function read(): Hints {
  try { return JSON.parse(localStorage.getItem(KEY) || '{}') as Hints } catch { return {} }
}

export function rememberAttempt(userId: string, examId: string, sessionId: string) {
  try {
    const hints = read()
    hints[userId] = { ...hints[userId], [examId]: sessionId }
    localStorage.setItem(KEY, JSON.stringify(hints))
  } catch { /* storage unavailable: hints are optional */ }
}

export const lastAttempt = (userId: string | undefined, examId: string): string | undefined =>
  userId ? read()[userId]?.[examId] : undefined

export const attemptsFor = (userId: string | undefined): Record<string, string> => (userId ? read()[userId] ?? {} : {})

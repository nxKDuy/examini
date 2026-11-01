import api from '../../lib/api'

// contracts/browser-integrity.md + exam-session-service/PHASE2_BROWSER_INTEGRITY.md. Signals are evidence, never proof.
export type WarningPolicy = 'LOG_ONLY' | 'WARN_ONLY'
export interface IntegrityPolicy {
  monitorTabSwitch: boolean; monitorWindowBlur: boolean; requireFullscreen: boolean; monitorCopy: boolean; monitorPaste: boolean
  monitorNetwork: boolean; warningPolicy: WarningPolicy; warningEventLimit: number
  /** Camera proctoring (video, face checks, snapshots for proctors). Absent on older exams = off. */
  requireCamera?: boolean
}
export type SignalType = 'TAB_SWITCH' | 'WINDOW_BLUR' | 'EXIT_FULLSCREEN' | 'COPY' | 'PASTE' | 'NETWORK_DISCONNECT' | 'NETWORK_RECONNECT'
  | 'FULLSCREEN_ENTER' | 'WINDOW_FOCUS' | 'PAGE_RELOAD' | 'SESSION_RESUME'
export interface SignalMetadata { durationMs?: number; visibilityState?: 'HIDDEN' | 'VISIBLE'; fromFullscreen?: boolean; toFullscreen?: boolean; networkState?: 'OFFLINE' | 'ONLINE' }
export interface SignalInput { clientEventId: string; type: SignalType; occurredAt?: string; metadata?: SignalMetadata | null }
export interface SignalEvent {
  id: string; clientEventId: string; examId: string; sessionId: string; userId: string; type: SignalType
  occurredAt?: string | null; receivedAt: string; severity: 'LOW' | 'MEDIUM'; metadata?: SignalMetadata | null; source: string
}
export type NotCountedReason = 'NOT_BUDGET_SIGNAL' | 'COALESCED' | 'STALE'
/** `counted`/`notCountedReason` come with contract A; an older server omits them and every budget signal counts. */
export interface Receipt {
  event: SignalEvent; duplicate: boolean; warningCount: number; warningLimit: number; warningRecommended: boolean; warning: string | null; autoSubmitted: boolean
  counted?: boolean; notCountedReason?: NotCountedReason | null
}
export interface Summary { examId: string; sessionId: string; totalEvents: number; counts: Record<SignalType, number> }
export interface IntegrityAudit {
  id: string; examId: string; sessionId: string; actorUserId: string | null; actorType: string; action: string
  triggerEventId: string | null; eventCount: number | null; occurredAt: string; metadata: string | null
}
export interface SpringPage<T> { content: T[]; totalElements: number; totalPages: number; number: number; size: number }
export interface IntegrityControl { examId: string; locked: boolean; unchanged: boolean; activeSessionsSubmitted: number; changedAt: string }

export const integrityApi = {
  examPolicy: (examId: string) => api.get<IntegrityPolicy>(`/exams/${examId}/integrity/policy`).then(r => r.data),
  savePolicy: (examId: string, policy: IntegrityPolicy) => api.put<IntegrityPolicy>(`/exams/${examId}/integrity/policy`, policy).then(r => r.data),
  lock: (examId: string, reason: string) => api.put<IntegrityControl>(`/exams/${examId}/integrity/lock`, { reason }).then(r => r.data),
  unlock: (examId: string) => api.put<IntegrityControl>(`/exams/${examId}/integrity/unlock`).then(r => r.data),
  sessionPolicy: (sessionId: string) => api.get<IntegrityPolicy>(`/sessions/${sessionId}/integrity/policy`).then(r => r.data),
  report: (sessionId: string, input: SignalInput) => api.post<Receipt>(`/sessions/${sessionId}/integrity/events`, input).then(r => r.data),
  examEvents: (examId: string, p: { type?: SignalType; page: number; size?: number }) =>
    api.get<SpringPage<SignalEvent>>(`/sessions/exam/${examId}/integrity/events`, { params: { size: 20, ...p } }).then(r => r.data),
  sessionEvents: (sessionId: string, page: number, size = 50) =>
    api.get<SpringPage<SignalEvent>>(`/sessions/${sessionId}/integrity/events`, { params: { page, size } }).then(r => r.data),
  summary: (sessionId: string) => api.get<Summary>(`/sessions/${sessionId}/integrity/summary`).then(r => r.data),
  audit: (sessionId: string) => api.get<SpringPage<IntegrityAudit>>(`/sessions/${sessionId}/integrity/audit`, { params: { page: 0, size: 50 } }).then(r => r.data),
}

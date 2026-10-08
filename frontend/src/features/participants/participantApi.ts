import api from '../../lib/api'

/** Contract: participant-attempt.md (MAIN). Counts, eligibility and attempt numbers are the server's; nothing is derived from the browser. */
export type ParticipantSource = 'ASSIGNED' | 'JOIN_CODE' | 'PUBLIC'
export type ParticipantStatus = 'ACTIVE' | 'REVOKED'
export interface Counts { registered: number; eligible: number; revoked: number }
export interface AttemptInfo {
  used: number; valid: number; invalidated: number; remaining: number; active: boolean
  latestSessionId?: string; latestAttemptNumber?: number; latestStatus?: string
}
export interface ParticipantRow {
  userId: string; displayName?: string; email?: string; source: ParticipantSource; status: ParticipantStatus
  joinedAt?: string; addedBy?: string; statusChangedAt?: string; statusChangedBy?: string; revocationReason?: string
  extraAttempts: number; attemptLimit: number; effectiveAttemptLimit: number; attempts?: AttemptInfo
}
export interface Roster { content: ParticipantRow[]; page: number; size: number; totalElements: number; totalPages: number; counts: Counts }

export type AddOutcome = 'ASSIGNED' | 'REINSTATED' | 'ALREADY_PARTICIPANT' | 'CAPACITY_FULL' | 'NOT_FOUND' | 'NOT_A_STUDENT'
export interface AddResult { input: string; userId?: string; displayName?: string; outcome: AddOutcome }
export interface AddResponse { results: AddResult[]; counts: Counts }
export type AddRequest = { userIds: string[] } | { emails: string[] }

export interface GrantView { grantId: string; quantity: number; reason?: string; createdAt: string }
export interface GrantResponse { userId: string; extraAttempts: number; effectiveAttemptLimit: number; grant: GrantView; replayed: boolean }
export interface GrantHistory { id: string; quantity: number; reason?: string; actorUserId?: string; createdAt: string }

/** One attempt as staff see it (GET /sessions/exam/{id}/attempts?userId=). */
export interface StaffAttempt {
  sessionId: string; userId: string; attemptNumber: number; status: string; startedAt?: string; endedAt?: string; expiresAt?: string
  invalidated: boolean; invalidatedAt?: string; invalidatedBy?: string; invalidationReason?: string
}
/** One attempt as the Student sees it (own). Public reason only; never staff notes. */
export interface OwnAttempt {
  sessionId: string; attemptNumber: number; status: string; startedAt?: string; endedAt?: string; expiresAt?: string
  invalidated: boolean; invalidatedAt?: string; invalidationReason?: string
}
export interface Mine {
  examId: string; status: ParticipantStatus; source?: ParticipantSource; joinedAt?: string; revokedAt?: string; revocationReason?: string
  attemptLimit: number; extraAttempts: number; effectiveAttemptLimit: number; used: number; remaining: number; activeAttempt: boolean
  followed?: boolean; attempts?: OwnAttempt[]; grants?: GrantView[]
}

export const participantApi = {
  roster: (examId: string, p: { status?: string; source?: string; userId?: string; page?: number; size?: number }) =>
    api.get<Roster>(`/exams/${examId}/participants`, { params: { size: 50, ...p } }).then(r => r.data),
  add: (examId: string, body: AddRequest) => api.post<AddResponse>(`/exams/${examId}/participants`, body).then(r => r.data),
  revoke: (examId: string, userId: string, reason?: string) => api.post<ParticipantRow>(`/exams/${examId}/participants/${userId}/revoke`, reason ? { reason } : {}).then(r => r.data),
  grant: (examId: string, userId: string, body: { quantity: number; reason: string; requestId: string }) =>
    api.post<GrantResponse>(`/exams/${examId}/participants/${userId}/attempt-grants`, body).then(r => r.data),
  grants: (examId: string, userId: string) => api.get<GrantHistory[]>(`/exams/${examId}/participants/${userId}/attempt-grants`).then(r => r.data),
  attempts: (examId: string, userId: string) => api.get<StaffAttempt[]>(`/sessions/exam/${examId}/attempts`, { params: { userId } }).then(r => r.data),
  invalidate: (sessionId: string, reason: string) => api.post<StaffAttempt>(`/sessions/${sessionId}/invalidate`, { reason }).then(r => r.data),
  mine: (examId: string) => api.get<Mine>(`/exams/${examId}/participation/me`).then(r => r.data),
  myAttempts: (examId: string) => api.get<OwnAttempt[]>(`/sessions/exam/${examId}/mine`).then(r => r.data),
}

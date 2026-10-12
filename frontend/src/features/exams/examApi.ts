import api from '../../lib/api'
import type { IntegrityPolicy } from '../integrity/integrityApi'
import type { BlueprintPreview, BlueprintRequest } from './builderModel'

export type ExamStatus = 'DRAFT' | 'PUBLISHED' | 'OPEN' | 'CLOSED' | 'COMPLETED'
export type AccessMode = 'ASSIGNED' | 'JOIN_CODE' | 'PUBLIC'
export type MemberRole = 'MANAGER' | 'PROCTOR'
export interface QuestionPoints { questionId: string; weight: number }
export interface Exam {
  id: string; code: string; title: string; description: string | null; ownerUserId: string
  type: 'PRACTICE' | 'EXAM'; durationMinutes: number; totalScore: number | null; passingScore: number | null
  questions: QuestionPoints[]; questionIds: string[]; shuffleQuestions: boolean; shuffleOptions: boolean
  status: ExamStatus; accessMode: AccessMode; maxParticipants: number; maxConcurrentSessions: number; attemptLimit: number
  startTime: string | null; endTime: string | null; createdAt: string
  integrityPolicy?: IntegrityPolicy | null; integrityLocked?: boolean
}
export interface Page<T> { content: T[]; totalElements: number; totalPages: number; number: number }
export interface ExamInput {
  title: string; description?: string; type: 'PRACTICE' | 'EXAM'; durationMinutes: number; passingScore: number | null
  shuffleQuestions: boolean; shuffleOptions: boolean
  startTime?: string | null; endTime?: string | null; questions: QuestionPoints[]
}
export interface Policy { accessMode: AccessMode; maxParticipants: number; maxConcurrentSessions: number; attemptLimit: number; joinCode?: string }
export interface SafeOption { id: string; content: string; displayOrder: number }
export interface SafeQuestion { questionId: string; questionVersion: number; questionType: string; content: string; points: number; position: number; options: SafeOption[] }
export interface Session {
  sessionId: string; examId: string; title: string; status: string; attemptNumber: number
  expiresAt: string; serverNow: string; questions: SafeQuestion[]; answers: Record<string, string>
}
/** Candidate-safe own result (manual-grading-api.md). Fields the policy hides are simply absent, never null-as-zero. */
export interface ResultItem {
  questionId: string; questionVersion: number; position: number; questionContent: string; questionType: string; pointValue?: number
  answer?: string; answerDisplay?: string | null; awardedPoints?: number; feedback?: string; correctAnswers?: string[]; explanation?: string | null
}
export interface ResultRevision { resultRevision: number; releasedAt?: string; score?: number; totalScore?: number; publicReason?: string }
export interface Result {
  sessionId: string; examId?: string; title: string; attemptNumber?: number; status: string; resultRevision?: number
  submittedAt?: string; gradedAt?: string; releasedAt?: string; score?: number; totalScore?: number; feedback?: string
  questions?: ResultItem[]; history?: ResultRevision[]; invalidated?: boolean; invalidatedAt?: string; invalidationReason?: string
}
export interface SessionSummary { sessionId: string; userId: string; status: string; attemptNumber: number; startedAt: string; expiresAt: string; invalidated?: boolean; invalidatedAt?: string; invalidationReason?: string }
export interface AuditEntry { id: string; examId: string; actorUserId: string; action: string; metadata: string | null; timestamp: string }

// The create/update DTO still carries proctoring booleans; Phase 1 has no proctoring, so they stay off.
const PHASE1_FLAGS = { requireCam: false, requireMic: false, blockTabSwitch: false, requireFullscreen: false }

export const examApi = {
  list: (page = 0, size = 50) => api.get<Page<Exam>>('/exams', { params: { page, size, sort: 'createdAt,desc' } }).then(r => r.data),
  /** Every exam visible to the user (owned or collaborating), following pagination. */
  listAll: async () => {
    const all: Exam[] = []
    for (let page = 0; ; page++) {
      const data = (await api.get<Page<Exam>>('/exams', { params: { page, size: 100, sort: 'createdAt,desc' } })).data
      all.push(...data.content)
      if (page + 1 >= data.totalPages) return all
    }
  },
  get: (id: string) => api.get<Exam>(`/exams/${id}`).then(r => r.data),
  create: (data: ExamInput) => api.post<Exam>('/exams/manual', { ...data, ...PHASE1_FLAGS }).then(r => r.data),
  update: (id: string, data: ExamInput) => api.put<Exam>(`/exams/${id}`, { ...data, ...PHASE1_FLAGS }).then(r => r.data),
  remove: (id: string) => api.delete(`/exams/${id}`),
  /** Draft only, nothing persisted (contracts/exam-builder.md); save goes through create/update. */
  blueprintPreview: (body: BlueprintRequest) => api.post<BlueprintPreview>('/exams/blueprint/preview', body).then(r => r.data),
  publish: (id: string) => api.put<Exam>(`/exams/${id}/publish`).then(r => r.data),
  open: (id: string) => api.put<Exam>(`/exams/${id}/open`).then(r => r.data),
  close: (id: string, reason?: string) => api.put<Exam>(`/exams/${id}/close`, reason ? { reason } : undefined).then(r => r.data),
  reopen: (id: string, reason: string) => api.put<Exam>(`/exams/${id}/reopen`, { reason }).then(r => r.data),
  policy: (id: string, data: Policy) => api.put(`/exams/${id}/policy`, data),
  members: (id: string) => api.get<Record<string, MemberRole>>(`/exams/${id}/members`).then(r => r.data),
  invite: (id: string, userId: string, role: MemberRole) => api.put(`/exams/${id}/members`, { userId, role }),
  removeMember: (id: string, userId: string) => api.delete(`/exams/${id}/members/${userId}`),
  invitations: () => api.get<Exam[]>('/exams/invitations').then(r => r.data),
  accept: (id: string) => api.post(`/exams/${id}/members/accept`),
  audit: (id: string) => api.get<AuditEntry[]>(`/exams/${id}/audit`).then(r => r.data),
  sessions: (id: string) => api.get<SessionSummary[]>(`/sessions/exam/${id}/monitor`).then(r => r.data),
  // Student
  my: () => api.get<Exam[]>('/exams/my').then(r => r.data),
  publicExams: () => api.get<Exam[]>('/exams/public').then(r => r.data),
  joinCode: (code: string) => api.post<Exam>(`/exams/code/${encodeURIComponent(code)}/join`).then(r => r.data),
  join: (id: string) => api.post<Exam>(`/exams/${id}/join`).then(r => r.data),
  lobby: (id: string) => api.get<Exam>(`/exams/${id}/lobby`).then(r => r.data),
  preflight: (id: string) => api.post<Exam>(`/exams/${id}/preflight`).then(r => r.data),
  start: (examId: string) => api.post<Session>('/sessions/start', null, { params: { examId } }).then(r => r.data),
  session: (id: string) => api.get<Session>(`/sessions/${id}`).then(r => r.data),
  answer: (id: string, questionId: string, content: string) => api.post(`/sessions/${id}/answers`, { content }, { params: { questionId } }),
  submit: (id: string) => api.post(`/sessions/${id}/submit`),
  result: (id: string) => api.get<Result>(`/grades/session/${id}`).then(r => r.data),
}

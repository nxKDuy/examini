import api from '../../lib/api'
export interface Exam {
  id: string; code: string; title: string; description: string; ownerUserId: string
  type: 'PRACTICE' | 'EXAM'; durationMinutes: number; totalScore: number; passingScore: number | null
  questions: {questionId: string; weight: number}[]; shuffleQuestions: boolean; shuffleOptions: boolean
  status: 'DRAFT' | 'PUBLISHED' | 'OPEN' | 'CLOSED' | 'COMPLETED'; questionIds: string[]
  accessMode: 'ASSIGNED' | 'JOIN_CODE' | 'PUBLIC'; maxParticipants: number; maxConcurrentSessions: number; attemptLimit: number
  startTime: string | null; endTime: string | null; createdAt: string
}
export interface Page<T> { content: T[]; totalElements: number; totalPages: number; number: number }
export interface CreateExamPayload {
  title: string; description?: string; type: 'PRACTICE' | 'EXAM'; durationMinutes: number; passingScore: number
  shuffleQuestions: boolean; shuffleOptions: boolean; requireCam: boolean; requireMic: boolean; blockTabSwitch: boolean; requireFullscreen: boolean
  startTime?: string; endTime?: string; questions: { questionId: string; weight: number }[]
}
export interface SafeQuestion { questionId: string; questionVersion: number; questionType: string; content: string; points: number; options: { id: string; content: string; displayOrder: number }[] }
export interface Session { sessionId: string; examId: string; title: string; status: string; attemptNumber: number; expiresAt: string; serverNow: string; questions: SafeQuestion[]; answers: Record<string, string> }
export interface Result { sessionId: string; title: string; status: string; score: number | null; totalScore: number | null; feedback: string | null }
export interface Monitor { sessionId: string; userId: string; status: string; attemptNumber: number; startedAt: string; expiresAt: string }
export function errorText(error: unknown) { const e = error as {response?: {data?: {message?: string; error?: string; detail?: string}}}; return e.response?.data?.detail || e.response?.data?.message || e.response?.data?.error || 'Yêu cầu thất bại. Vui lòng thử lại.' }
export const examApi = {
  selectQuestions: (params: {categoryId?: string; difficulty?: string; page: number; size: number}) => api.get<Page<import('../question-bank/questionApi').Question>>('/questions',{params:{...params,status:'APPROVED'}}).then(r=>r.data),
  getExams: (params: { page?: number; size?: number }) => api.get<Page<Exam>>('/exams', { params: { size: 12, sort: 'createdAt,desc', ...params } }).then(r => r.data),
  createExam: (data: CreateExamPayload) => api.post<Exam>('/exams/manual', data).then(r => r.data),
  updateExam: (id: string, data: CreateExamPayload) => api.put<Exam>(`/exams/${id}`, data).then(r => r.data),
  deleteExam: (id: string) => api.delete(`/exams/${id}`),
  publishExam: (id: string) => api.put<Exam>(`/exams/${id}/publish`).then(r => r.data),
  open: (id: string) => api.put(`/exams/${id}/open`), close: (id: string) => api.put(`/exams/${id}/close`),
  policy: (id: string, data: Pick<Exam, 'accessMode'|'maxParticipants'|'maxConcurrentSessions'|'attemptLimit'>) => api.put(`/exams/${id}/policy`, data),
  members: (id: string) => api.get<Record<string, 'MANAGER'|'PROCTOR'>>(`/exams/${id}/members`).then(r=>r.data),
  invite: (id: string, userId: string, role: string) => api.put(`/exams/${id}/members`, {userId, role}),
  removeMember: (id: string, userId: string) => api.delete(`/exams/${id}/members/${userId}`),
  invitations: () => api.get<Exam[]>('/exams/invitations').then(r=>r.data),
  accept: (id: string) => api.post(`/exams/${id}/members/accept`),
  participants: (id: string) => api.get<Record<string,string>>(`/exams/${id}/participants`).then(r=>r.data),
  assign: (id: string, userId: string) => api.put(`/exams/${id}/participants/${userId}`),
  unassign: (id: string, userId: string) => api.delete(`/exams/${id}/participants/${userId}`),
  monitor: (id: string) => api.get<Monitor[]>(`/sessions/exam/${id}/monitor`).then(r=>r.data),
  my: () => api.get<Exam[]>('/exams/my').then(r=>r.data),
  public: () => api.get<Exam[]>('/exams/public').then(r=>r.data),
  joinCode: (code: string) => api.post<Exam>(`/exams/code/${encodeURIComponent(code)}/join`).then(r=>r.data),
  join: (id: string) => api.post<Exam>(`/exams/${id}/join`).then(r=>r.data),
  lobby: (id: string) => api.get<Exam>(`/exams/${id}/lobby`).then(r=>r.data),
  preflight: (id: string) => api.post<Exam>(`/exams/${id}/preflight`).then(r=>r.data),
  start: (examId: string) => api.post<Session>('/sessions/start', null, {params: {examId}}).then(r=>r.data),
  session: (id: string) => api.get<Session>(`/sessions/${id}`).then(r=>r.data),
  answer: (id: string, questionId: string, content: string) => api.post(`/sessions/${id}/answers`, {content}, {params: {questionId}}),
  submit: (id: string) => api.post(`/sessions/${id}/submit`),
  result: (id: string) => api.get<Result>(`/grades/session/${id}`).then(r=>r.data),
}

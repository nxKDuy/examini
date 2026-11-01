import api from '../../../lib/api'
import type { CameraEventType } from './cameraModel'

// contracts/camera-proctoring.md. Student routes post evidence of their own attempt; review routes are exam staff only.
export interface CameraEvent {
  id: string; sessionId: string; userId: string; type: CameraEventType; reason: string; occurredAt: string | null; receivedAt: string
  detail: Record<string, number>; hasSnapshot: boolean
}
export interface CameraRecording { mimeType: string; chunks: number; bytes: number; startedAt: string; lastChunkAt: string | null }
/** Media are deleted `days` after the attempt's deadline (0 = kept); the violation log stays. */
export interface CameraRetention { days: number; deleteAt: string | null; purgedAt: string | null }
export type IdentityOutcome = 'ENROLLED' | 'MATCH' | 'MISMATCH'
/** Face verification at exam entry against the profile from the student's first camera exam. */
export interface CameraIdentity {
  outcome: IdentityOutcome; distance: number | null; threshold: number; attempts: number; maxAttempts: number
  /** false while the student must retry; true once verified, enrolled or out of attempts (flagged). */
  mayContinue: boolean; checkedAt: string; profileCreatedAt: string | null
}
export interface SessionCamera {
  sessionId: string; userId: string; reference: CameraEvent | null; recording: CameraRecording | null; events: CameraEvent[]; retention?: CameraRetention
  identity?: CameraIdentity | null; profile?: boolean
}
export interface SessionCameraSummary { sessionId: string; userId: string; counts: Partial<Record<CameraEventType, number>>; violations: number; lastEventAt: string | null; recording: CameraRecording | null; retention?: CameraRetention; identity?: IdentityOutcome | null }
export interface CameraReport { clientEventId: string; type: CameraEventType; occurredAt: string; detail?: Record<string, number> }

export const cameraApi = {
  report: (sessionId: string, event: CameraReport, snapshot?: Blob | null) => {
    const form = new FormData()
    form.append('event', new Blob([JSON.stringify(event)], { type: 'application/json' }))
    if (snapshot) form.append('snapshot', snapshot, 'snapshot.jpg')
    return api.post<{ event: CameraEvent; duplicate: boolean }>(`/sessions/${sessionId}/camera/events`, form, { headers: { 'Content-Type': 'multipart/form-data' } }).then(r => r.data)
  },
  identity: (sessionId: string, descriptor: Float32Array, photo: Blob) => {
    const form = new FormData()
    form.append('identity', new Blob([JSON.stringify({ descriptor: Array.from(descriptor, v => Math.round(v * 1e6) / 1e6) })], { type: 'application/json' }))
    form.append('photo', photo, 'photo.jpg')
    return api.post<CameraIdentity>(`/sessions/${sessionId}/camera/identity`, form, { headers: { 'Content-Type': 'multipart/form-data' } }).then(r => r.data)
  },
  profilePhoto: (sessionId: string) => api.get<Blob>(`/sessions/${sessionId}/camera/profile-photo`, { responseType: 'blob' }).then(r => r.data),
  resetProfile: (sessionId: string) => api.delete(`/sessions/${sessionId}/camera/profile`).then(() => undefined),
  chunk: (sessionId: string, seq: number, data: Blob) =>
    api.post<{ seq: number; stored: boolean; chunks: number; bytes: number }>(`/sessions/${sessionId}/camera/video`, data,
      { params: { seq }, headers: { 'Content-Type': data.type.split(';')[0] || 'video/webm' } }).then(r => r.data),
  session: (sessionId: string) => api.get<SessionCamera>(`/sessions/${sessionId}/camera`).then(r => r.data),
  exam: (examId: string) => api.get<SessionCameraSummary[]>(`/sessions/exam/${examId}/camera`).then(r => r.data),
  /** Binary evidence goes through the authenticated client, then becomes an object URL for <img>/<video>. */
  snapshot: (sessionId: string, eventId: string) => api.get<Blob>(`/sessions/${sessionId}/camera/events/${eventId}/snapshot`, { responseType: 'blob' }).then(r => r.data),
  video: (sessionId: string) => api.get<Blob>(`/sessions/${sessionId}/camera/video`, { responseType: 'blob' }).then(r => r.data),
}

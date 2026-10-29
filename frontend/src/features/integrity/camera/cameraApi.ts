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
export interface SessionCamera { sessionId: string; userId: string; reference: CameraEvent | null; recording: CameraRecording | null; events: CameraEvent[]; retention?: CameraRetention }
export interface SessionCameraSummary { sessionId: string; userId: string; counts: Partial<Record<CameraEventType, number>>; violations: number; lastEventAt: string | null; recording: CameraRecording | null; retention?: CameraRetention }
export interface CameraReport { clientEventId: string; type: CameraEventType; occurredAt: string; detail?: Record<string, number> }

export const cameraApi = {
  report: (sessionId: string, event: CameraReport, snapshot?: Blob | null) => {
    const form = new FormData()
    form.append('event', new Blob([JSON.stringify(event)], { type: 'application/json' }))
    if (snapshot) form.append('snapshot', snapshot, 'snapshot.jpg')
    return api.post<{ event: CameraEvent; duplicate: boolean }>(`/sessions/${sessionId}/camera/events`, form, { headers: { 'Content-Type': 'multipart/form-data' } }).then(r => r.data)
  },
  chunk: (sessionId: string, seq: number, data: Blob) =>
    api.post<{ seq: number; stored: boolean; chunks: number; bytes: number }>(`/sessions/${sessionId}/camera/video`, data,
      { params: { seq }, headers: { 'Content-Type': data.type.split(';')[0] || 'video/webm' } }).then(r => r.data),
  session: (sessionId: string) => api.get<SessionCamera>(`/sessions/${sessionId}/camera`).then(r => r.data),
  exam: (examId: string) => api.get<SessionCameraSummary[]>(`/sessions/exam/${examId}/camera`).then(r => r.data),
  /** Binary evidence goes through the authenticated client, then becomes an object URL for <img>/<video>. */
  snapshot: (sessionId: string, eventId: string) => api.get<Blob>(`/sessions/${sessionId}/camera/events/${eventId}/snapshot`, { responseType: 'blob' }).then(r => r.data),
  video: (sessionId: string) => api.get<Blob>(`/sessions/${sessionId}/camera/video`, { responseType: 'blob' }).then(r => r.data),
}

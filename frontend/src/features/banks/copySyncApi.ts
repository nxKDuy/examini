import api from '../../lib/api'

// Copy version sync (contracts/question-copy-sync.md). Redacted fields carry flags only; their values never reach the client.
export type SyncChoice = 'KEEP_LOCAL' | 'TAKE_SOURCE'
export type SyncAvailability = 'AVAILABLE' | 'UNAVAILABLE'
export interface FieldChange {
  field: string; sourceChanged: boolean; localChanged: boolean; conflict: boolean; redacted: boolean
  reference: unknown; local: unknown; latest: unknown
}
export interface SyncExpected { localVersion: number; referenceSourceVersion: number; cursorRevision: number; latestSourceVersion: number | null; availability: SyncAvailability }
export interface QuestionSyncPreview extends SyncExpected { questionId: string; localStatus: string; changes: FieldChange[] }
export interface SetItemChange {
  sourceQuestionId: string; localQuestionId: string | null; kind: 'ADDED' | 'UPDATED' | 'REMOVED'
  referencePosition: number | null; localPosition: number | null; latestPosition: number | null
  referenceVersion: number | null; latestVersion: number | null; localChanged: boolean; conflict: boolean
}
export interface SetSyncPreview extends SyncExpected {
  questionSetId: string; metadataChanges: FieldChange[]; itemChanges: SetItemChange[]
  sourceReordered: boolean; localReordered: boolean; reorderConflict: boolean
  /** Full orders (contract E). Older providers omit them. */
  referenceOrder?: OrderEntry[]; sourceOrder?: OrderEntry[]; localOrder?: OrderEntry[]
}
export interface OrderEntry { position: number; sourceQuestionId: string | null; localQuestionId: string | null; content: string | null }
/** `localVersion` is what was recorded at apply time; a correction (append-only) gives the effective version. */
export interface SyncEvent {
  id: string; resourceKind: string; localVersion: number; sourceVersion: number; actions: string; actorUserId: string; createdAt: string
  correctedLocalVersion?: number | null; correctionReason?: string | null; correctedAt?: string | null
}
export interface SyncApplyBody {
  expectedLocalVersion: number; expectedReferenceSourceVersion: number; expectedCursorRevision: number; expectedSourceVersion: number
  decisions: Record<string, SyncChoice>
}
export type SyncKind = 'question' | 'set'

const base = (kind: SyncKind, id: string) => `/${kind === 'question' ? 'questions' : 'question-sets'}/${id}/copy-sync`

export const copySyncApi = {
  question: (id: string) => api.get<QuestionSyncPreview>(base('question', id)).then(r => r.data),
  set: (id: string) => api.get<SetSyncPreview>(base('set', id)).then(r => r.data),
  apply: <T,>(kind: SyncKind, id: string, body: SyncApplyBody) => api.post<T>(base(kind, id), body).then(r => r.data),
  history: (kind: SyncKind, id: string) => api.get<SyncEvent[]>(`${base(kind, id)}/history`).then(r => r.data),
}

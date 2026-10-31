/** Pure helpers for question media, passages and per-type limits (kept free of the API client so tests can load them). */

export interface MediaRefLike { id: string; kind: 'IMAGE' | 'AUDIO'; name?: string | null }
export interface PresentationLike {
  media?: MediaRefLike[] | null
  optionMedia?: Record<string, string> | null
  passage?: { key: string; title?: string | null; content: string; media?: MediaRefLike[] | null } | null
  maxPlays?: number | null
  minWords?: number | null
  maxWords?: number | null
  maxRecordSeconds?: number | null
}

export const IMAGE_MAX_BYTES = 3 * 1024 * 1024
export const AUDIO_MAX_BYTES = 20 * 1024 * 1024
export const MEDIA_ACCEPT = 'image/png,image/jpeg,image/webp,image/gif,audio/mpeg,audio/mp4,audio/ogg,audio/wav,audio/webm,.mp3,.m4a,.ogg,.wav,.webm'

export type MediaProblem = 'TYPE' | 'IMAGE_SIZE' | 'AUDIO_SIZE'
/** Client-side pre-check; the server sniffs the real type from the bytes. */
export function mediaProblem(file: { type: string; size: number; name: string }): MediaProblem | null {
  const kind = mediaKindOf(file)
  if (!kind) return 'TYPE'
  if (kind === 'IMAGE' && file.size > IMAGE_MAX_BYTES) return 'IMAGE_SIZE'
  if (kind === 'AUDIO' && file.size > AUDIO_MAX_BYTES) return 'AUDIO_SIZE'
  return null
}
export function mediaKindOf(file: { type: string; name: string }): 'IMAGE' | 'AUDIO' | null {
  if (file.type.startsWith('image/')) return 'IMAGE'
  if (file.type.startsWith('audio/') || /\.(mp3|m4a|ogg|wav|webm)$/i.test(file.name)) return 'AUDIO'
  return null
}

/** Words in a Vietnamese/English answer: runs of letters/digits separated by whitespace or punctuation. */
export function countWords(text: string): number {
  const m = text.trim().match(/[\p{L}\p{N}]+(?:['’-][\p{L}\p{N}]+)*/gu)
  return m ? m.length : 0
}

export type WordState = 'ok' | 'short' | 'long'
export function wordState(words: number, p: PresentationLike | null | undefined): WordState {
  if (p?.maxWords && words > p.maxWords) return 'long'
  if (p?.minWords && words > 0 && words < p.minWords) return 'short'
  return 'ok'
}

/** Play counts per attempt+media live in sessionStorage so a reload keeps the count in this tab. */
export interface PlayStore { getItem(k: string): string | null; setItem(k: string, v: string): void }
const playKey = (sessionId: string, mediaId: string) => `examini-plays:${sessionId}:${mediaId}`
export function playsUsed(store: PlayStore | null, sessionId: string, mediaId: string): number {
  try { const n = Number(store?.getItem(playKey(sessionId, mediaId))); return Number.isFinite(n) && n > 0 ? Math.floor(n) : 0 } catch { return 0 }
}
export function recordPlay(store: PlayStore | null, sessionId: string, mediaId: string): number {
  const n = playsUsed(store, sessionId, mediaId) + 1
  try { store?.setItem(playKey(sessionId, mediaId), String(n)) } catch { /* storage blocked: the count stays in memory only */ }
  return n
}

/** Questions that share a passage key and sit next to each other form one block (the server keeps them together when shuffling). */
export interface Block<Q> { passageKey: string | null; start: number; end: number; questions: Q[] }
export function passageBlocks<Q extends { presentation?: PresentationLike | null }>(questions: Q[]): Block<Q>[] {
  const out: Block<Q>[] = []
  questions.forEach((q, i) => {
    const key = q.presentation?.passage?.key ?? null
    const last = out[out.length - 1]
    if (key && last && last.passageKey === key) { last.questions.push(q); last.end = i }
    else out.push({ passageKey: key, start: i, end: i, questions: [q] })
  })
  return out
}
/** "Câu 3–5" range of the passage block containing index i, or null for a standalone question. */
export function blockOf<Q extends { presentation?: PresentationLike | null }>(questions: Q[], i: number): Block<Q> | null {
  const b = passageBlocks(questions).find(x => i >= x.start && i <= x.end)
  return b && b.passageKey ? b : null
}

/** Speaking answers are stored as JSON metadata, the audio itself lives in the session service. */
export interface SpokenAnswer { audio: true; seconds: number; bytes: number; type: string }
export function spokenAnswer(value: string | null | undefined): SpokenAnswer | null {
  if (!value) return null
  try {
    const v = JSON.parse(value)
    return v && v.audio === true && typeof v.seconds === 'number' ? v as SpokenAnswer : null
  } catch { return null }
}

/** Drops empty parts so a question without media sends no presentation at all. */
export function cleanPresentation(p: PresentationLike, type: string): PresentationLike | null {
  const media = (p.media ?? []).filter(m => m && m.id)
  const optionMedia = Object.fromEntries(Object.entries(p.optionMedia ?? {}).filter(([, v]) => !!v))
  const pm = (p.passage?.media ?? []).filter(m => m && m.id)
  const passage = p.passage && (p.passage.content.trim() || pm.length) ? { ...p.passage, title: p.passage.title?.trim() || null, content: p.passage.content.trim(), media: pm } : null
  const hasAudio = media.some(m => m.kind === 'AUDIO') || (passage?.media ?? []).some(m => m.kind === 'AUDIO')
  const out: PresentationLike = {
    ...(media.length ? { media } : {}),
    ...(Object.keys(optionMedia).length && (type === 'MULTIPLE_CHOICE' || type === 'MULTIPLE_RESPONSE') ? { optionMedia } : {}),
    ...(passage ? { passage } : {}),
    ...(hasAudio && p.maxPlays ? { maxPlays: p.maxPlays } : {}),
    ...(type === 'ESSAY' && p.minWords ? { minWords: p.minWords } : {}),
    ...(type === 'ESSAY' && p.maxWords ? { maxWords: p.maxWords } : {}),
    ...(type === 'SPEAKING' ? { maxRecordSeconds: p.maxRecordSeconds || 120 } : {}),
  }
  return Object.keys(out).length ? out : null
}

/**
 * FILL_BLANK and MATCHING (contracts/question-media-and-skills.md).
 *  FILL_BLANK: the stem marks each gap with ___ (3+ underscores); option i lists the accepted answers of gap i ("a | b").
 *    Answer: JSON object gap (option) id -> typed text.
 *  MATCHING: each option is one pair "left => right". Students see the left items and
 *    presentation.matchTargets (the right items, alphabetical). Answer: JSON object left (option) id -> chosen right item.
 * Pure functions, safe to load in node tests.
 */
export const BLANK = /_{3,}/g
export const STRUCTURED_TYPES = ['FILL_BLANK', 'MATCHING'] as const
export const isStructured = (t: string) => (STRUCTURED_TYPES as readonly string[]).includes(t)
export const MAX_BLANKS = 20
export const MAX_PAIRS = 10

export function countBlanks(content: string | null | undefined): number {
  return (content ?? '').match(BLANK)?.length ?? 0
}
/** Text between the gaps: n gaps give n + 1 pieces. */
export function splitBlanks(content: string | null | undefined): string[] {
  return (content ?? '').split(BLANK)
}
export function alternatives(option: string | null | undefined): string[] {
  return (option ?? '').split('|').map(s => s.trim()).filter(Boolean)
}
export function pairOf(option: string | null | undefined): { left: string; right: string } | null {
  const s = option ?? ''
  const i = s.indexOf('=>')
  if (i < 0) return null
  const left = s.slice(0, i).trim(), right = s.slice(i + 2).trim()
  return left && right ? { left, right } : null
}
export const joinPair = (left: string, right: string) => `${left.trim()} => ${right.trim()}`

export function parseTexts(value: string | null | undefined): Record<string, string> {
  if (!value) return {}
  try {
    const v = JSON.parse(value)
    if (!v || typeof v !== 'object' || Array.isArray(v)) return {}
    return Object.fromEntries(Object.entries(v).filter(([, s]) => typeof s === 'string')) as Record<string, string>
  } catch { return {} }
}
/** Sets one gap / pair; blank text removes it. Empty answer = ''. */
export function setText(value: string, id: string, text: string): string {
  const m = parseTexts(value)
  if (text.trim()) m[id] = text; else delete m[id]
  return Object.keys(m).length ? JSON.stringify(m) : ''
}
export const filledCount = (value: string, ids: string[]) => { const m = parseTexts(value); return ids.filter(id => (m[id] ?? '').trim()).length }

/** Same comparison as grading-service: Unicode NFC, spaces collapsed, case-insensitive. */
export const fold = (s: string) => s.normalize('NFC').replace(/\s+/g, ' ').trim().toLowerCase()

/** Gaps / pairs right out of the total, as grading-service counts them (options carry the key). */
export function partsRight(type: string, options: { id: string; content: string }[], value: string): number {
  const given = parseTexts(value)
  let right = 0
  for (const o of options) {
    const v = given[o.id]
    if (!v || !v.trim()) continue
    if (type === 'MATCHING') { const p = pairOf(o.content); if (p && fold(p.right) === fold(v)) right++ }
    else if (alternatives(o.content).some(a => fold(a) === fold(v))) right++
  }
  return right
}

/** Editor check; null when the gaps/pairs are consistent, otherwise the Vietnamese message to show. */
export function checkStructured(type: string, content: string, parts: string[] | { left: string; right: string }[]): string | null {
  if (type === 'FILL_BLANK') {
    const n = countBlanks(content)
    if (n < 1 || n > MAX_BLANKS) return 'Đánh dấu 1–20 chỗ trống trong nội dung bằng ___ (ba dấu gạch dưới).'
    const gaps = parts as string[]
    if (gaps.length !== n || gaps.some(g => alternatives(g).length === 0)) return 'Nhập đáp án cho mọi chỗ trống.'
    return null
  }
  const pairs = parts as { left: string; right: string }[]
  if (pairs.length < 2 || pairs.length > MAX_PAIRS) return 'Nối cột cần 2–10 cặp.'
  if (pairs.some(p => !p.left.trim() || !p.right.trim() || p.left.includes('=>') || p.right.includes('=>'))) return 'Nhập đủ hai vế cho mọi cặp (không dùng “=>” trong nội dung).'
  const l = new Set(pairs.map(p => fold(p.left))), r = new Set(pairs.map(p => fold(p.right)))
  if (l.size !== pairs.length || r.size !== pairs.length) return 'Các vế trong câu nối cột không được trùng nhau.'
  return null
}

/** The key in reading form, as grading-service shows it: "(1) Hà Nội / Ha Noi" or "Việt Nam → Hà Nội". */
export function keyLines(type: string, options: { content: string; displayOrder?: number }[]): string[] {
  const sorted = [...options].sort((a, b) => (a.displayOrder ?? 0) - (b.displayOrder ?? 0))
  if (type === 'FILL_BLANK') return sorted.map((o, i) => `(${i + 1}) ${alternatives(o.content).join(' / ')}`)
  return sorted.map(o => { const p = pairOf(o.content); return p ? `${p.left} → ${p.right}` : o.content })
}

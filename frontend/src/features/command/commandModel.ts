/**
 * Quick-jump palette (Ctrl+K): ranking without accents so "lich thi", "lịch thi" and "LICH" all find "Lịch thi".
 * Pure functions, tested in node.
 */
export interface Command { id: string; label: string; hint?: string; group: string; to: string; keywords?: string }

export const fold = (s: string) => s.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/g, 'd').replace(/Đ/g, 'D').toLowerCase().replace(/\s+/g, ' ').trim()

/** 0 = no match. Whole-label prefix beats word prefix beats substring; every query word must match somewhere. */
export function score(c: Command, query: string): number {
  const q = fold(query)
  if (!q) return 1
  const label = fold(c.label), all = fold(`${c.label} ${c.hint ?? ''} ${c.keywords ?? ''}`)
  const words = q.split(' ')
  if (!words.every(w => all.includes(w))) return 0
  if (label.startsWith(q)) return 100 - label.length / 100
  if (label.split(' ').some(w => w.startsWith(words[0]))) return 60 - label.length / 100
  if (label.includes(q)) return 40
  return 10
}

export function rank(commands: Command[], query: string, limit = 12): Command[] {
  return commands.map((c, i) => ({ c, s: score(c, query), i }))
    .filter(x => x.s > 0)
    .sort((a, b) => b.s - a.s || a.i - b.i)
    .slice(0, limit).map(x => x.c)
}

const isMac = () => typeof navigator !== 'undefined' && /Mac|iPhone|iPad/.test(navigator.platform)
export const shortcutLabel = () => (isMac() ? '⌘K' : 'Ctrl K')

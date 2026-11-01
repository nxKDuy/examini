/** Pure helpers for the small SVG charts (kept free of React so node tests can load them). */

/** Validated categorical order for charts (brand blue lifted into the lightness band, then orange). */
export const SERIES_COLORS = ['#3355e6', '#e8590c'] as const

/** A "nice" axis maximum and tick values (1, 2, 2.5, 5 × 10^n steps), at least `min`. */
export function niceScale(max: number, ticks = 4, min = 1): { max: number; ticks: number[] } {
  const top = Math.max(min, Number.isFinite(max) ? max : 0)
  const raw = top / ticks
  const mag = 10 ** Math.floor(Math.log10(raw))
  const step = [1, 2, 2.5, 5, 10].map(m => m * mag).find(s => s >= raw) ?? 10 * mag
  const niceMax = Math.ceil(top / step) * step
  const out: number[] = []
  for (let v = 0; v <= niceMax + 1e-9; v += step) out.push(Math.round(v * 1e6) / 1e6)
  return { max: niceMax, ticks: out }
}

/** x positions spread evenly over [left, right]; a single point sits in the middle. */
export function xPositions(n: number, left: number, right: number): number[] {
  if (n <= 0) return []
  if (n === 1) return [(left + right) / 2]
  return Array.from({ length: n }, (_, i) => left + ((right - left) * i) / (n - 1))
}

/** Which labels to print on a dense axis: about `max` of them, always first and last. */
export function labelEvery(n: number, max = 7): (i: number) => boolean {
  if (n <= max) return () => true
  const step = Math.ceil((n - 1) / (max - 1))
  return i => i === 0 || i === n - 1 || (i % step === 0 && n - 1 - i >= step / 2)
}

/** Index of the nearest x position to a pointer x (for the hover crosshair). */
export function nearestIndex(xs: number[], x: number): number {
  let best = 0
  for (let i = 1; i < xs.length; i++) if (Math.abs(xs[i] - x) < Math.abs(xs[best] - x)) best = i
  return best
}

/** "30/10" short day label from an ISO date (YYYY-MM-DD). */
export const dayLabel = (iso: string) => { const [, m, d] = iso.split('-'); return `${Number(d)}/${Number(m)}` }

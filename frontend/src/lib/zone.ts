import { locale } from './i18n'
// Time zone arithmetic on top of Intl, with no date library. Server instants are UTC;
// Exams carry their own IANA zone and the schedule API wants an offset that is valid
// for the wall-clock time in that zone (DST gap rejected, overlap needs a choice).

const cache = new Map<string, Intl.DateTimeFormat>()
function dtf(tz: string) {
  let f = cache.get(tz)
  if (!f) {
    f = new Intl.DateTimeFormat('en-US', { timeZone: tz, hourCycle: 'h23', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit' })
    cache.set(tz, f)
  }
  return f
}

export interface Wall { y: number; m: number; d: number; h: number; mi: number; s: number }

export function wallParts(ms: number, tz: string): Wall {
  const p: Record<string, number> = {}
  for (const part of dtf(tz).formatToParts(new Date(ms))) if (part.type !== 'literal') p[part.type] = Number(part.value)
  return { y: p.year, m: p.month, d: p.day, h: p.hour === 24 ? 0 : p.hour, mi: p.minute, s: p.second }
}

const pad = (n: number, w = 2) => String(n).padStart(w, '0')
export const wallString = (w: Wall) => `${pad(w.y, 4)}-${pad(w.m)}-${pad(w.d)}T${pad(w.h)}:${pad(w.mi)}`
export const dayString = (w: Wall) => `${pad(w.y, 4)}-${pad(w.m)}-${pad(w.d)}`
const wallUtcMs = (w: Wall) => Date.UTC(w.y, w.m - 1, w.d, w.h, w.mi, w.s)

/** Offset of `tz` from UTC at that instant, in minutes (east positive). */
export function offsetMinutes(ms: number, tz: string): number {
  return Math.round((wallUtcMs(wallParts(ms, tz)) - Math.floor(ms / 1000) * 1000) / 60000)
}

export function formatOffset(min: number): string {
  const sign = min < 0 ? '-' : '+'
  const a = Math.abs(min)
  return `${sign}${pad(Math.floor(a / 60))}:${pad(a % 60)}`
}

/** "2030-01-02T08:00:00+07:00" for an instant as seen in `tz`. */
export function toIsoWithOffset(ms: number, tz: string): string {
  const w = wallParts(ms, tz)
  return `${wallString(w)}:${pad(w.s)}${formatOffset(offsetMinutes(ms, tz))}`
}

/** Offset ISO for a server instant string, keeping its fractional seconds so an untouched value round-trips exactly. */
export function rawToOffsetIso(raw: string, tz: string): string {
  const ms = Date.parse(raw)
  const frac = /\.(\d+)/.exec(raw)?.[1]
  const iso = toIsoWithOffset(ms, tz)
  return frac ? iso.replace(/(:\d{2})([+-]\d{2}:\d{2})$/, `$1.${frac}$2`) : iso
}

export const instantToWall = (ms: number, tz: string) => wallString(wallParts(ms, tz))
export const instantToDay = (ms: number, tz: string) => dayString(wallParts(ms, tz))

export interface WallOption { ms: number; offsetMin: number }
export type WallResolution = { kind: 'invalid' } | { kind: 'gap' } | { kind: 'valid' | 'overlap'; options: WallOption[] }

/** All instants whose wall time in `tz` equals `wall` (YYYY-MM-DDTHH:mm): 0 = DST gap, 2 = overlap. */
export function resolveWall(wall: string, tz: string): WallResolution {
  const m = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})$/.exec(wall)
  if (!m) return { kind: 'invalid' }
  const [y, mo, d, h, mi] = m.slice(1).map(Number)
  const base = Date.UTC(y, mo - 1, d, h, mi)
  if (Number.isNaN(base)) return { kind: 'invalid' }
  const candidates = new Set([offsetMinutes(base - 864e5, tz), offsetMinutes(base + 864e5, tz)])
  const options = new Map<number, WallOption>()
  for (const off of candidates) {
    const ms = base - off * 60000
    if (instantToWall(ms, tz) === wall) options.set(ms, { ms, offsetMin: off })
  }
  const list = [...options.values()].sort((a, b) => a.ms - b.ms)
  if (list.length === 0) return { kind: 'gap' }
  return { kind: list.length === 1 ? 'valid' : 'overlap', options: list }
}

/** Instant at which the given calendar day starts in `tz` (skips a DST gap at midnight). */
export function dayStart(day: string, tz: string): number {
  for (let minute = 0; minute < 180; minute += 15) {
    const wall = `${day}T${pad(Math.floor(minute / 60))}:${pad(minute % 60)}`
    const r = resolveWall(wall, tz)
    if (r.kind === 'valid' || r.kind === 'overlap') return r.options[0].ms
  }
  return Date.parse(`${day}T00:00:00Z`)
}

export function addDays(day: string, n: number): string {
  const [y, m, d] = day.split('-').map(Number)
  const t = new Date(Date.UTC(y, m - 1, d + n))
  return `${pad(t.getUTCFullYear(), 4)}-${pad(t.getUTCMonth() + 1)}-${pad(t.getUTCDate())}`
}

/** 0 = Monday ... 6 = Sunday. */
export function weekdayIndex(day: string): number {
  const [y, m, d] = day.split('-').map(Number)
  return (new Date(Date.UTC(y, m - 1, d)).getUTCDay() + 6) % 7
}

export const viewerZone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'

export function isValidZone(tz: string) {
  try { new Intl.DateTimeFormat('en-US', { timeZone: tz }); return tz.length > 0 } catch { return false }
}

/** "GMT+7" / "GMT+5:30" / "UTC". */
export function zoneLabel(ms: number, tz: string): string {
  if (tz === 'UTC') return 'UTC'
  try {
    const part = new Intl.DateTimeFormat('en-US', { timeZone: tz, timeZoneName: 'shortOffset' }).formatToParts(new Date(ms)).find(p => p.type === 'timeZoneName')
    if (part) return part.value === 'GMT' ? 'UTC' : part.value
  } catch { /* fall through */ }
  return `GMT${formatOffset(offsetMinutes(ms, tz))}`
}

const COMMON = ['Asia/Ho_Chi_Minh', 'UTC', 'Asia/Bangkok', 'Asia/Singapore', 'Asia/Tokyo', 'Europe/Paris', 'Europe/London', 'America/New_York', 'America/Los_Angeles', 'Australia/Sydney']
export function zoneOptions(extra: string[] = []): string[] {
  let all: string[] = []
  try { all = (Intl as unknown as { supportedValuesOf?: (k: string) => string[] }).supportedValuesOf?.('timeZone') ?? [] } catch { all = [] }
  return [...new Set([...extra, ...COMMON, ...all, 'UTC'])].filter(isValidZone)
}

const fmtCache = new Map<string, Intl.DateTimeFormat>()
function fmt(tz: string, key: string, opts: Intl.DateTimeFormatOptions) {
  const k = `${locale()}|${tz}|${key}`
  let f = fmtCache.get(k)
  if (!f) { f = new Intl.DateTimeFormat(locale(), { timeZone: tz, ...opts }); fmtCache.set(k, f) }
  return f
}
export const formatTimeIn = (ms: number, tz: string) => fmt(tz, 't', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' }).format(new Date(ms))
export const formatDateIn = (ms: number, tz: string) => fmt(tz, 'd', { weekday: 'short', day: '2-digit', month: '2-digit', year: 'numeric' }).format(new Date(ms))
export const formatDateTimeIn = (ms: number, tz: string) => `${formatTimeIn(ms, tz)} ${fmt(tz, 'dm', { day: '2-digit', month: '2-digit', year: 'numeric' }).format(new Date(ms))}`

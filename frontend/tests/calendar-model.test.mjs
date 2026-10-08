import { test } from 'node:test'
import assert from 'node:assert/strict'
import { registerHooks } from 'node:module'
import { readFileSync, existsSync } from 'node:fs'
import ts from 'typescript'
import axios from 'axios'

registerHooks({
  resolve(specifier, context, next) {
    if (specifier.startsWith('.') && context.parentURL?.includes('/src/')) {
      const url = new URL(specifier, context.parentURL)
      for (const ext of ['.ts', '.tsx']) if (existsSync(new URL(url.href + ext))) return { url: url.href + ext, shortCircuit: true }
    }
    return next(specifier, context)
  },
  load(url, context, next) {
    if (/\.tsx?$/.test(url)) {
      const source = readFileSync(new URL(url), 'utf8').replace('import.meta.env.VITE_API_BASE_URL', "'/'")
      return { format: 'module', shortCircuit: true, source: ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, jsx: ts.JsxEmit.ReactJSX } }).outputText }
    }
    return next(url, context)
  },
})
const storage = new Map()
globalThis.localStorage = { getItem: k => storage.get(k) ?? null, setItem: (k, v) => storage.set(k, v), removeItem: k => storage.delete(k) }
globalThis.window = { localStorage: globalThis.localStorage }
// The shared client copies axios defaults when it is created, so install the adapter before importing it.
const seen = []
axios.defaults.adapter = async config => {
  seen.push(config.params.page)
  const page = config.params.page, total = 3
  const content = Array.from({ length: page === 2 ? 1 : 100 }, (_, i) => ({ examId: `${page}-${i}`, serverNow: '2030-01-10T00:00:10Z' }))
  return { status: 200, statusText: 'OK', headers: {}, config, data: { content, totalPages: total, last: page === total - 1 } }
}
const m = await import('../src/features/calendar/calendarModel.ts')
const { fetchCalendar } = await import('../src/features/calendar/calendarApi.ts')

const VN = 'Asia/Ho_Chi_Minh', PARIS = 'Europe/Paris'
const item = (over = {}) => ({ examId: 'e', examTitle: 'Đề', status: 'OPEN', startAt: null, endAt: null, timezone: VN, durationMinutes: 30, deadlineRule: 'FULL_DURATION',
  canJoin: false, canStart: false, canResume: false, reasonCode: null, attemptsRemaining: 1, effectiveExpiresAt: null, relation: 'ASSIGNED', followed: false,
  participant: true, participantCount: null, maxParticipants: null, calendarState: 'UPCOMING', latestSessionId: null, latestSessionStatus: null, ...over })

test('span: window, start-only, end-only and unscheduled', () => {
  assert.deepEqual(m.itemSpan(item({ startAt: '2030-01-02T01:00:00Z', endAt: '2030-01-02T03:00:00Z' })), { start: Date.parse('2030-01-02T01:00:00Z'), end: Date.parse('2030-01-02T03:00:00Z'), kind: 'window' })
  assert.equal(m.itemSpan(item({ startAt: '2030-01-02T01:00:00Z' })).kind, 'open-start')
  assert.equal(m.itemSpan(item({ endAt: '2030-01-02T03:00:00Z' })).kind, 'deadline')
  assert.equal(m.itemSpan(item()), null)
})

test('month grid is Monday-first and covers whole weeks; range stays within the backend limit', () => {
  const days = m.visibleDays('month', '2030-01-15')
  assert.equal(days.length % 7, 0)
  assert.equal(days[0], '2029-12-31') // Mon before Tue 2030-01-01
  assert.ok(days.includes('2030-01-31'))
  assert.equal(m.visibleDays('week', '2030-01-09')[0], '2030-01-07')
  assert.equal(m.visibleDays('day', '2030-01-09').length, 1)
  const r = m.agendaRange('2030-06-01', VN)
  assert.ok((Date.parse(r.to) - Date.parse(r.from)) / 864e5 <= 366)
  assert.equal(m.shiftAnchor('month', '2030-01-31', 1), '2030-02-01')
  assert.equal(m.shiftAnchor('week', '2030-01-09', -1), '2030-01-02')
})

test('range boundaries use the viewer zone day starts', () => {
  const r = m.rangeOf(['2030-03-31'], PARIS)
  assert.equal(r.from, '2030-03-30T23:00:00.000Z')
  assert.equal((Date.parse(r.to) - Date.parse(r.from)) / 3600e3, 23)
})

test('agenda groups: today, upcoming, later, unscheduled, past (viewer zone)', () => {
  const now = Date.parse('2030-01-10T05:00:00Z') // 12:00 Jan 10 in VN
  const at = (day, hh = '09') => new Date(Date.parse(`${day}T${hh}:00:00+07:00`)).toISOString()
  const items = [
    item({ examId: 'past', startAt: at('2030-01-08'), endAt: at('2030-01-08', '10') }),
    item({ examId: 'today', startAt: at('2030-01-10'), endAt: at('2030-01-10', '11') }),
    item({ examId: 'running', startAt: at('2030-01-09'), endAt: at('2030-01-12') }),
    item({ examId: 'soon', startAt: at('2030-01-14') }),
    item({ examId: 'later', startAt: at('2030-02-20') }),
    item({ examId: 'none' }),
  ]
  const g = Object.fromEntries(m.agendaGroups(items, now, VN).map(x => [x.key, x.items.map(i => i.examId)]))
  assert.deepEqual(g, { today: ['running', 'today'], upcoming: ['soon'], later: ['later'], unscheduled: ['none'], past: ['past'] })
  // 7-day boundary: day +8 is already "later"
  assert.equal(m.agendaGroups([item({ examId: 'x', startAt: at('2030-01-18') })], now, VN).find(x => x.key === 'later').items.length, 1)
})

test('an exam crossing midnight appears on both days; none on grids when unscheduled', () => {
  const crossing = item({ startAt: '2030-01-10T16:00:00Z', endAt: '2030-01-10T18:00:00Z' }) // 23:00 -> 01:00 VN
  assert.equal(m.itemsOnDay([crossing], '2030-01-10', VN).length, 1)
  assert.equal(m.itemsOnDay([crossing], '2030-01-11', VN).length, 1)
  assert.equal(m.itemsOnDay([crossing], '2030-01-12', VN).length, 0)
  assert.equal(m.itemsOnDay([item()], '2030-01-10', VN).length, 0)
  // same instants, viewer in Paris: both inside one local evening
  assert.equal(m.itemsOnDay([crossing], '2030-01-10', PARIS).length, 1)
  assert.equal(m.itemsOnDay([crossing], '2030-01-11', PARIS).length, 0)
})

test('day layout: overlapping blocks share columns, separate ones do not; clipping flags', () => {
  const a = item({ examId: 'a', startAt: '2030-01-10T01:00:00Z', endAt: '2030-01-10T03:00:00Z' })
  const b = item({ examId: 'b', startAt: '2030-01-10T02:00:00Z', endAt: '2030-01-10T04:00:00Z' })
  const c = item({ examId: 'c', startAt: '2030-01-10T05:00:00Z', endAt: '2030-01-10T06:00:00Z' })
  const blocks = Object.fromEntries(m.layoutDay([a, b, c], '2030-01-10', 'UTC').map(x => [x.item.examId, x]))
  assert.equal(blocks.a.cols, 2); assert.equal(blocks.b.cols, 2); assert.notEqual(blocks.a.col, blocks.b.col)
  assert.equal(blocks.c.cols, 1); assert.equal(blocks.c.col, 0)
  assert.ok(Math.abs(blocks.a.top - (1 / 24) * 100) < 1e-9)
  const long = m.layoutDay([item({ startAt: '2030-01-09T20:00:00Z', endAt: '2030-01-10T02:00:00Z' })], '2030-01-10', 'UTC')[0]
  assert.equal(long.clippedStart, true); assert.equal(long.top, 0)
})

test('states: Student and Teacher vocab, reason codes and unknowns', () => {
  assert.equal(m.stateInfo('MISSED').tone, 'danger')
  assert.equal(m.stateInfo('DRAFT').tone, 'warning')
  assert.equal(m.stateInfo('AVAILABLE').tone, 'success')
  assert.equal(m.stateInfo('SOMETHING_NEW').label, 'SOMETHING_NEW')
  assert.match(m.reasonText('BEFORE_START_AT'), /xem trước/)
  assert.match(m.reasonText('INSUFFICIENT_TIME'), /không đủ/)
  assert.equal(m.reasonText(null), null)
  assert.equal(m.ruleLabel('CAPPED_AT_END'), 'Nộp đúng giờ kết thúc')
})

test('window text shows the Exam zone, and the viewer line only when it differs', () => {
  const e = item({ startAt: '2030-01-10T01:00:00Z', endAt: '2030-01-10T03:00:00Z' })
  const same = m.windowText(e, VN)
  assert.match(same.primary, /08:00–10:00/); assert.match(same.zone, /GMT\+7 · Asia\/Ho_Chi_Minh/); assert.equal(same.mine, undefined)
  const paris = m.windowText(e, PARIS)
  assert.match(paris.mine, /02:00–04:00/); assert.match(paris.mine, /GMT\+1/)
  assert.equal(m.windowText(item(), VN).primary, 'Chưa đặt lịch')
  assert.match(m.windowText(item({ endAt: '2030-01-10T03:00:00Z' }), VN).primary, /^Đến /)
})

test('countdown is display text derived from server-corrected now', () => {
  const now = Date.parse('2030-01-10T00:00:00Z')
  assert.equal(m.describeCountdown(2 * 86400e3 + 3 * 3600e3), '2 ngày 3 giờ')
  assert.equal(m.describeCountdown(3 * 3600e3 + 12 * 60e3), '3 giờ 12 phút')
  assert.equal(m.describeCountdown(754e3), '12:34')
  assert.equal(m.countdownLine(item({ startAt: '2030-01-10T01:00:00Z' }), now), 'Mở sau 1 giờ')
  assert.equal(m.countdownLine(item({ calendarState: 'IN_PROGRESS', effectiveExpiresAt: '2030-01-10T00:10:00Z' }), now), 'Còn 10:00 để nộp')
  assert.equal(m.countdownLine(item({ calendarState: 'SUBMITTED', startAt: '2030-01-10T01:00:00Z' }), now), null)
  assert.equal(m.countdownLine(item({ calendarState: 'AVAILABLE', canStart: true, startAt: '2030-01-09T00:00:00Z', endAt: '2030-01-10T00:30:00Z' }), now), 'Đóng sau 30:00')
})

test('fetchCalendar reads every page and corrects the clock from serverNow', async () => {
  const before = Date.now()
  const r = await fetchCalendar('2030-01-01T00:00:00Z', '2030-02-01T00:00:00Z', true)
  assert.deepEqual(seen, [0, 1, 2]); assert.equal(r.items.length, 201); assert.equal(r.truncated, false)
  assert.ok(Math.abs(r.clockOffset - (Date.parse('2030-01-10T00:00:10Z') - before)) < 5000)
})

test('crowded clusters collapse into one "+N" block beyond the column cap', () => {
  const mk = (i) => item({ examId: `x${i}`, startAt: '2030-01-10T01:00:00Z', endAt: '2030-01-10T02:00:00Z' })
  const blocks = m.layoutDay([mk(1), mk(2), mk(3), mk(4), mk(5), mk(6)], '2030-01-10', 'UTC')
  assert.equal(blocks[0].cols, 6)
  const { shown, overflow } = m.collapseCrowded(blocks, 3)
  assert.equal(shown.length, 2); assert.ok(shown.every(b => b.cols === 3))
  assert.equal(overflow.length, 1); assert.equal(overflow[0].hidden.length, 4); assert.equal(overflow[0].col, 2)
  const few = m.collapseCrowded(m.layoutDay([mk(1), mk(2)], '2030-01-10', 'UTC'), 3)
  assert.equal(few.overflow.length, 0); assert.equal(few.shown.length, 2)
  const day = m.collapseCrowded(blocks, 6)
  assert.equal(day.overflow.length, 0); assert.equal(day.shown.length, 6)
})

const LA = 'America/Los_Angeles'
test('DST regression: a 10:00 Exam sits on the 10:00 row on 23h and 25h days (wall-clock axis)', () => {
  const tenAm = 10 / 24 * 100
  // 2030-03-10 is the spring-forward day in Los Angeles (23h). 10:00 PDT = 17:00Z.
  const spring = m.layoutDay([item({ examId: 's', startAt: '2030-03-10T17:00:00Z', endAt: '2030-03-10T18:00:00Z' })], '2030-03-10', LA)[0]
  assert.ok(Math.abs(spring.top - tenAm) < 1e-9, `spring top ${spring.top}`)
  assert.ok(Math.abs(spring.height - 100 / 24) < 1e-9)
  // 2030-11-03 is the fall-back day (25h). 10:00 PST = 18:00Z.
  const fall = m.layoutDay([item({ examId: 'f', startAt: '2030-11-03T18:00:00Z', endAt: '2030-11-03T19:00:00Z' })], '2030-11-03', LA)[0]
  assert.ok(Math.abs(fall.top - tenAm) < 1e-9, `fall top ${fall.top}`)
  // Before the change on the same spring day: 01:00 PST = 09:00Z is the 01:00 row, not 01:00*24/23
  const early = m.layoutDay([item({ examId: 'e', startAt: '2030-03-10T09:00:00Z', endAt: '2030-03-10T09:30:00Z' })], '2030-03-10', LA)[0]
  assert.ok(Math.abs(early.top - 100 / 24) < 1e-9)
  // Clipped at both ends still spans the whole axis
  const all = m.layoutDay([item({ startAt: '2030-03-09T00:00:00Z', endAt: '2030-03-12T00:00:00Z' })], '2030-03-10', LA)[0]
  assert.equal(all.top, 0); assert.ok(Math.abs(all.height - 100) < 1e-9)
})

test('DST regression: the now line is on the wall-clock row, only on today', () => {
  const nowSpring = Date.parse('2030-03-10T17:30:00Z') // 10:30 PDT
  assert.ok(Math.abs(m.nowTop(nowSpring, '2030-03-10', LA) - 10.5 / 24 * 100) < 1e-9)
  assert.equal(m.nowTop(nowSpring, '2030-03-11', LA), null)
  const nowFall = Date.parse('2030-11-03T18:30:00Z') // 10:30 PST
  assert.ok(Math.abs(m.nowTop(nowFall, '2030-11-03', LA) - 10.5 / 24 * 100) < 1e-9)
})

test('DST regression: both ends of a window carry their own offset when they differ', () => {
  // Paris falls back on 2030-10-27 03:00 -> 02:00. 02:30 happens twice: +02:00 (00:30Z) then +01:00 (01:30Z).
  const overlap = m.windowText({ startAt: '2030-10-27T00:30:00Z', endAt: '2030-10-27T01:30:00Z', timezone: PARIS }, PARIS)
  assert.match(overlap.primary, /02:30 GMT\+2–02:30 GMT\+1/)
  assert.match(overlap.zone, /GMT\+2 → GMT\+1 · Europe\/Paris/)
  // Same wall labels, different instants, no ambiguity once offsets are shown
  const viewer = m.windowText({ startAt: '2030-10-27T00:30:00Z', endAt: '2030-10-27T01:30:00Z', timezone: VN }, PARIS)
  assert.match(viewer.mine, /02:30 GMT\+2–02:30 GMT\+1/)
  // No DST change inside the window: unchanged, concise form
  const plain = m.windowText({ startAt: '2030-06-10T01:00:00Z', endAt: '2030-06-10T03:00:00Z', timezone: PARIS }, PARIS)
  assert.equal(plain.primary.includes('GMT'), false)
  assert.match(plain.zone, /^GMT\+2 · Europe\/Paris$/)
  // Window spanning the change over several days
  const span = m.windowText({ startAt: '2030-10-26T12:00:00Z', endAt: '2030-10-28T12:00:00Z', timezone: PARIS }, PARIS)
  assert.match(span.primary, /GMT\+2 → .* GMT\+1/)
})

test('start-only Exams that are still running are never "past"; finished ones are', () => {
  const now = Date.parse('2030-01-10T05:00:00Z')
  const longAgo = '2030-01-07T01:00:00Z' // three days ago, no endAt
  const open = item({ examId: 'open', startAt: longAgo, calendarState: 'AVAILABLE', canStart: true })
  const upcomingState = item({ examId: 'joinreq', startAt: longAgo, calendarState: 'JOIN_REQUIRED' })
  const closed = item({ examId: 'closed', startAt: longAgo, calendarState: 'CLOSED' })
  const submitted = item({ examId: 'done', startAt: longAgo, calendarState: 'SUBMITTED' })
  const teacherOpen = item({ examId: 'tOpen', startAt: longAgo, calendarState: 'OPEN', relation: 'OWNER' })
  const g = Object.fromEntries(m.agendaGroups([open, upcomingState, closed, submitted, teacherOpen], now, VN).map(x => [x.key, x.items.map(i => i.examId)]))
  assert.deepEqual(g.today, ['open', 'joinreq', 'tOpen'])
  assert.deepEqual(g.past.sort(), ['closed', 'done'])
  assert.equal(m.isOpenEnded(open), true); assert.equal(m.isOpenEnded(closed), false)
  assert.equal(m.isOpenEnded(item({ startAt: longAgo, endAt: '2030-01-08T01:00:00Z', calendarState: 'AVAILABLE' })), false)
  // The synthetic 1h display block is only a drawing aid: eligibility fields are untouched
  assert.equal(open.canStart, true)
  assert.equal(m.itemSpan(open).kind, 'open-start')
})

test('nextBoundary: the earliest future instant the backend answer can change at', () => {
  const now = Date.parse('2030-01-10T00:00:00Z')
  const items = [
    item({ startAt: '2030-01-09T00:00:00Z', endAt: '2030-01-10T02:00:00Z' }),
    item({ startAt: '2030-01-10T01:00:00Z' }),
    item({ effectiveExpiresAt: '2030-01-10T00:30:00Z' }),
    item({}),
  ]
  assert.equal(m.nextBoundary(items, now), Date.parse('2030-01-10T00:30:00Z'))
  assert.equal(m.nextBoundary(items, Date.parse('2030-01-10T00:30:00Z')), Date.parse('2030-01-10T01:00:00Z'))
  assert.equal(m.nextBoundary(items, Date.parse('2030-01-11T00:00:00Z')), null)
  assert.equal(m.nextBoundary([], now), null)
})

test('block label: each end carries its offset across a DST change, concise otherwise', () => {
  // Los Angeles falls back on 2030-11-03 02:00 -> 01:00: 01:30 happens as PDT (08:30Z) and again as PST (09:30Z).
  const overlap = m.blockRangeLabel({ startAt: '2030-11-03T08:30:00Z', endAt: '2030-11-03T09:30:00Z', timezone: LA })
  assert.equal(overlap, '01:30 GMT-7–01:30 GMT-8')
  assert.equal(m.blockRangeLabel({ startAt: '2030-11-03T18:00:00Z', endAt: '2030-11-03T19:00:00Z', timezone: LA }), '10:00–11:00 GMT-8')
  assert.equal(m.blockRangeLabel({ startAt: '2030-11-03T18:00:00Z', endAt: null, timezone: LA }), 'từ 10:00 GMT-8')
  assert.equal(m.blockRangeLabel({ startAt: null, endAt: '2030-11-03T19:00:00Z', timezone: LA }), 'đóng 11:00 GMT-8')
  assert.equal(m.blockRangeLabel({ startAt: null, endAt: null, timezone: LA }), '')
})

test('revoked participation (new backend values) has its own wording, never the generic fallback', () => {
  assert.equal(m.stateInfo('PARTICIPATION_REVOKED').label, 'Đã bị gỡ'); assert.match(m.reasonText('PARTICIPATION_REVOKED'), /không còn trong danh sách/)
  assert.equal(m.relationLabel('REVOKED'), 'Đã bị gỡ')
})

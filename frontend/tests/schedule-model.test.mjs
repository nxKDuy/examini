import { test } from 'node:test'
import assert from 'node:assert/strict'
import { registerHooks } from 'node:module'
import { readFileSync, existsSync } from 'node:fs'
import ts from 'typescript'

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
const m = await import('../src/features/calendar/scheduleModel.ts')
const z = await import('../src/lib/zone.ts')

const sched = (over = {}) => ({ examId: 'e', startAt: null, endAt: null, timezone: 'Asia/Ho_Chi_Minh', deadlineRule: 'FULL_DURATION', autoOpen: false, autoClose: false,
  reminderOffsetsMinutes: [], scheduleRevision: 0, lifecycleRevision: 0, serverNow: '2030-01-01T00:00:00Z', ...over })

test('untouched instants round-trip with the Exam-zone offset and exact fractional seconds', () => {
  const raw = '2030-01-02T01:00:00.123456Z'
  assert.equal(z.rawToOffsetIso(raw, 'Asia/Ho_Chi_Minh'), '2030-01-02T08:00:00.123456+07:00')
  assert.equal(z.rawToOffsetIso('2030-01-02T01:00:00Z', 'Europe/Paris'), '2030-01-02T02:00:00+01:00')
  const s = sched({ startAt: raw, endAt: '2030-01-02T03:00:00Z', scheduleRevision: 3, reminderOffsetsMinutes: [15, 1440] })
  const input = m.toInput(m.initForm(s), s)
  assert.equal(input.startAt, '2030-01-02T08:00:00.123456+07:00')
  assert.equal(input.endAt, '2030-01-02T10:00:00+07:00')
  assert.equal(input.expectedRevision, 3)
  assert.equal(m.isChanged(m.initForm(s), s), false)
})

test('new schedules default to 24h + 15m reminders; saved empty reminders stay off', () => {
  assert.deepEqual(m.initForm(sched()).reminders, [1440, 15])
  const off = m.initForm(sched({ scheduleRevision: 2, startAt: '2030-01-02T01:00:00Z' }))
  assert.equal(off.remindersOff, true)
  assert.deepEqual(m.toInput(off, sched({ scheduleRevision: 2, startAt: '2030-01-02T01:00:00Z' })).reminderOffsetsMinutes, [])
})

test('editing a wall time uses the Exam zone; DST gap blocks and overlap needs a choice', () => {
  const s = sched({ timezone: 'Europe/Paris', scheduleRevision: 1, startAt: '2030-06-01T07:00:00Z' })
  let f = m.initForm(s)
  f = { ...f, end: m.setWall('2030-06-01T12:00', f.tz) }
  assert.equal(m.toInput(f, s).endAt, '2030-06-01T12:00:00+02:00')
  const gap = { ...f, start: m.setWall('2030-03-31T02:30', f.tz) }
  assert.equal(m.fieldIssue(gap.start, gap.tz).kind, 'gap')
  assert.ok(m.validateForm(gap, s, 'DRAFT').some(x => /chuyển giờ mùa hè/.test(x)))
  const overlap = { ...f, start: m.setWall('2030-10-27T02:30', f.tz), end: EMPTY_END }
  const issue = m.fieldIssue(overlap.start, overlap.tz)
  assert.equal(issue.kind, 'overlap')
  const chosen = { ...overlap, start: m.chooseOption(overlap.start, issue.options[1].ms, overlap.tz) }
  assert.equal(m.fieldIssue(chosen.start, chosen.tz), null)
  assert.equal(m.toInput(chosen, s).startAt, '2030-10-27T02:30:00+01:00')
  assert.equal(m.toInput({ ...overlap, start: m.chooseOption(overlap.start, issue.options[0].ms, overlap.tz) }, s).startAt, '2030-10-27T02:30:00+02:00')
})
const EMPTY_END = { wall: '', ms: null, raw: null }

test('changing the zone keeps the instant and only re-derives the wall time', () => {
  const s = sched({ scheduleRevision: 1, startAt: '2030-01-02T01:00:00Z', endAt: '2030-01-02T03:00:00Z' })
  const paris = m.changeZone(m.initForm(s), 'Europe/Paris')
  assert.equal(paris.start.wall, '2030-01-02T02:00')
  assert.equal(paris.start.ms, Date.parse('2030-01-02T01:00:00Z'))
  assert.equal(m.toInput(paris, s).startAt, '2030-01-02T02:00:00+01:00')
  assert.equal(m.isChanged(paris, s), true) // zone differs
})

test('validation: ordering, rule needs endAt, auto flags, reminders, reason after publish', () => {
  const s = sched({ scheduleRevision: 1, startAt: '2030-01-02T01:00:00Z', endAt: '2030-01-02T03:00:00Z' })
  const base = m.initForm(s)
  assert.deepEqual(m.validateForm(base, s, 'PUBLISHED'), [])
  const backwards = { ...base, end: m.setWall('2030-01-02T07:00', base.tz) }
  assert.ok(m.validateForm(backwards, s, 'DRAFT').some(x => /sau giờ bắt đầu/.test(x)))
  const needsEnd = { ...base, rule: 'CAPPED_AT_END', end: EMPTY_END }
  assert.ok(m.validateForm(needsEnd, s, 'DRAFT').some(x => /cần có giờ kết thúc/.test(x)))
  const noEndAuto = { ...base, autoClose: true, end: EMPTY_END }
  assert.equal(m.toInput(noEndAuto, s).autoClose, false)
  const changed = { ...base, rule: 'CAPPED_AT_END' }
  assert.ok(m.validateForm(changed, s, 'PUBLISHED').some(x => /lý do/.test(x)))
  assert.equal(m.validateForm({ ...changed, reason: 'Dời theo lịch trường' }, s, 'PUBLISHED').length, 0)
  assert.equal(m.validateForm(changed, s, 'DRAFT').length, 0) // DRAFT edits need no reason
  assert.equal(m.toInput({ ...changed, reason: ' Dời  ' }, s).reason, 'Dời')
})

test('OPEN exams: only extending endAt is allowed', () => {
  const s = sched({ scheduleRevision: 2, startAt: '2030-01-02T01:00:00Z', endAt: '2030-01-02T03:00:00Z' })
  const base = m.initForm(s)
  assert.ok(m.validateForm({ ...base, end: m.setWall('2030-01-02T09:00', base.tz), reason: 'x' }, s, 'OPEN').some(x => /kéo dài/.test(x)))
  assert.deepEqual(m.validateForm({ ...base, end: m.setWall('2030-01-02T12:00', base.tz), reason: 'Kéo dài thêm 1 giờ' }, s, 'OPEN'), [])
  const noEnd = sched({ scheduleRevision: 2, startAt: '2030-01-02T01:00:00Z' })
  assert.ok(m.validateForm({ ...m.initForm(noEnd), end: m.setWall('2030-01-02T12:00', 'Asia/Ho_Chi_Minh'), reason: 'x' }, noEnd, 'OPEN').some(x => /không thêm giờ kết thúc/.test(x)))
})

test('reminder helpers: labels, limits, custom units, send times', () => {
  assert.deepEqual([15, 60, 180, 1440, 2880, 10080].map(m.offsetLabel), ['15 phút', '1 giờ', '3 giờ', '24 giờ', '2 ngày', '7 ngày'])
  assert.equal(m.offsetLabel(90), '90 phút')
  assert.equal(m.customToMinutes(2, 'day'), 2880)
  assert.equal(m.customToMinutes(1.5, 'hour'), 90)
  assert.deepEqual(m.normalizeReminders([15, 1440, 15, 60]), [1440, 60, 15])
  assert.equal(m.reminderIssue([0]).includes('43200'), true)
  assert.equal(m.reminderIssue([43201]) !== null, true)
  assert.equal(m.reminderIssue(Array.from({ length: 11 }, (_, i) => i + 1)) !== null, true)
  assert.equal(m.reminderIssue([1440, 15]), null)
  const start = Date.parse('2030-01-02T01:00:00Z')
  assert.equal(m.reminderSend(start, 60, start - 3600e3 - 1).past, false)
  assert.equal(m.reminderSend(start, 60, start - 3600e3).past, true)
})

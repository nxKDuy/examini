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
const z = await import('../src/lib/zone.ts')

test('offsets: fixed, DST and half-hour zones', () => {
  const jan = Date.parse('2030-01-02T00:00:00Z'), jul = Date.parse('2030-07-02T00:00:00Z')
  assert.equal(z.offsetMinutes(jan, 'Asia/Ho_Chi_Minh'), 420)
  assert.equal(z.offsetMinutes(jan, 'Europe/Paris'), 60)
  assert.equal(z.offsetMinutes(jul, 'Europe/Paris'), 120)
  assert.equal(z.offsetMinutes(jan, 'Asia/Kolkata'), 330)
  assert.equal(z.offsetMinutes(jan, 'UTC'), 0)
  assert.equal(z.formatOffset(330), '+05:30')
  assert.equal(z.formatOffset(-300), '-05:00')
})

test('ISO with offset for the Exam zone, not the viewer zone', () => {
  assert.equal(z.toIsoWithOffset(Date.parse('2030-01-02T01:00:00Z'), 'Asia/Ho_Chi_Minh'), '2030-01-02T08:00:00+07:00')
  assert.equal(z.toIsoWithOffset(Date.parse('2030-07-02T06:00:00Z'), 'Europe/Paris'), '2030-07-02T08:00:00+02:00')
  assert.equal(z.instantToWall(Date.parse('2030-01-02T17:30:00Z'), 'Asia/Ho_Chi_Minh'), '2030-01-03T00:30')
})

test('wall time resolves to exactly one instant outside transitions', () => {
  const r = z.resolveWall('2030-01-02T08:00', 'Asia/Ho_Chi_Minh')
  assert.equal(r.kind, 'valid')
  assert.equal(new Date(r.options[0].ms).toISOString(), '2030-01-02T01:00:00.000Z')
  assert.equal(z.resolveWall('2030-06-15T09:00', 'Europe/Paris').options[0].offsetMin, 120)
})

test('DST gap is detected and never silently shifted', () => {
  assert.equal(z.resolveWall('2030-03-31T02:30', 'Europe/Paris').kind, 'gap')
  assert.equal(z.resolveWall('2030-03-10T02:30', 'America/New_York').kind, 'gap')
  assert.equal(z.resolveWall('2030-03-31T03:30', 'Europe/Paris').kind, 'valid')
})

test('DST overlap offers both offsets, earlier instant first', () => {
  const paris = z.resolveWall('2030-10-27T02:30', 'Europe/Paris')
  assert.equal(paris.kind, 'overlap')
  assert.deepEqual(paris.options.map(o => o.offsetMin), [120, 60])
  assert.ok(paris.options[0].ms < paris.options[1].ms)
  const ny = z.resolveWall('2030-11-03T01:30', 'America/New_York')
  assert.equal(ny.kind, 'overlap')
  assert.deepEqual(ny.options.map(o => o.offsetMin), [-240, -300])
  assert.equal(z.resolveWall('not-a-date', 'UTC').kind, 'invalid')
})

test('day arithmetic and day starts follow the zone, including 23h/25h days', () => {
  assert.equal(z.addDays('2030-02-28', 1), '2030-03-01')
  assert.equal(z.addDays('2030-01-01', -1), '2029-12-31')
  assert.equal(z.weekdayIndex('2030-01-07'), 0) // Monday
  assert.equal(z.weekdayIndex('2030-01-06'), 6) // Sunday
  const start = z.dayStart('2030-03-31', 'Europe/Paris'), next = z.dayStart('2030-04-01', 'Europe/Paris')
  assert.equal((next - start) / 3600e3, 23)
  const fall = z.dayStart('2030-10-27', 'Europe/Paris'), after = z.dayStart('2030-10-28', 'Europe/Paris')
  assert.equal((after - fall) / 3600e3, 25)
  assert.equal(z.instantToDay(Date.parse('2030-01-02T20:00:00Z'), 'Asia/Ho_Chi_Minh'), '2030-01-03')
})

test('labels and formatting use the requested zone', () => {
  assert.equal(z.zoneLabel(Date.parse('2030-01-02T00:00:00Z'), 'Asia/Ho_Chi_Minh'), 'GMT+7')
  assert.equal(z.zoneLabel(Date.parse('2030-01-02T00:00:00Z'), 'UTC'), 'UTC')
  assert.equal(z.zoneLabel(Date.parse('2030-01-02T00:00:00Z'), 'Asia/Kolkata'), 'GMT+5:30')
  assert.equal(z.formatTimeIn(Date.parse('2030-01-02T01:00:00Z'), 'Asia/Ho_Chi_Minh'), '08:00')
  assert.equal(z.formatTimeIn(Date.parse('2030-01-02T01:00:00Z'), 'Europe/Paris'), '02:00')
  assert.equal(z.isValidZone('Asia/Ho_Chi_Minh'), true)
  assert.equal(z.isValidZone('Mars/Olympus'), false)
  assert.ok(z.zoneOptions().includes('Asia/Ho_Chi_Minh'))
})

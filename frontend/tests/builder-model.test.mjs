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
    if (/\.tsx?$/.test(url)) return { format: 'module', shortCircuit: true, source: ts.transpileModule(readFileSync(new URL(url), 'utf8'), { compilerOptions: { module: ts.ModuleKind.ESNext } }).outputText }
    return next(url, context)
  },
})
const m = await import('../src/features/exams/builderModel.ts')

test('selection keeps order, drops duplicates by questionId and questions already in the exam', () => {
  const r = m.addToSelection(['a'], ['b', 'a', 'c', 'b', 'x'], ['x'])
  assert.deepEqual(r.next, ['a', 'b', 'c'])
  assert.equal(r.added, 2)
  assert.equal(r.duplicates, 3)
  assert.equal(r.overflow, 0)
})

test('selection never passes the 200-question exam limit and reports the overflow', () => {
  const inExam = Array.from({ length: 198 }, (_, i) => `e${i}`)
  const r = m.addToSelection([], ['a', 'b', 'c', 'd'], inExam)
  assert.deepEqual(r.next, ['a', 'b'])
  assert.equal(r.overflow, 2)
  assert.equal(m.addToSelection(['a', 'b'], ['z'], inExam).added, 0)
})

test('batch chunks respect the provider limit of 200 ids', () => {
  const ids = Array.from({ length: 450 }, (_, i) => i)
  assert.deepEqual(m.chunk(ids).map(c => c.length), [200, 200, 50])
  assert.deepEqual(m.chunk([]), [])
})

test('points: total ignores invalid weights; bulk set keeps every question', () => {
  assert.equal(m.totalPoints([{ weight: 1 }, { weight: 2.5 }, { weight: NaN }]), 3.5)
  assert.deepEqual(m.setAllPoints([{ id: 'a', weight: 1 }, { id: 'b', weight: 3 }], 2), [{ id: 'a', weight: 2 }, { id: 'b', weight: 2 }])
  assert.ok(m.SKIP_REASON.NOT_ACTIVE)
})

const row = (o = {}) => ({ key: 'k', count: 2, pointsPerQuestion: 1, ...o })
const preview = { rows: [
  { rowIndex: 0, questions: [{ questionId: 'a' }, { questionId: 'b' }] },
  { rowIndex: 1, questions: [{ questionId: 'c' }] },
] }

test('blueprint validation: counts, points and the 200 total including questions already in the exam', () => {
  assert.equal(m.blueprintError([]), 'Thêm ít nhất một dòng khung đề.')
  assert.match(m.blueprintError([row({ count: 0 })]), /Dòng 1/)
  assert.match(m.blueprintError([row({ pointsPerQuestion: 0 })]), /Điểm mỗi câu/)
  assert.match(m.blueprintError([row({ count: 150 }), row({ count: 40 })], 20), /vượt quá 200/)
  assert.equal(m.blueprintError([row()]), null)
})

test('request carries only set filters, pins capped by count, and exclusions never include pinned ids', () => {
  const req = m.blueprintRequest([row({ difficulty: 'EASY' }), row({ count: 1 })], true, { 0: ['a', 'b', 'x'], 1: ['c'] }, ['c', 'z', 'z'])
  assert.deepEqual(req.rows[0], { difficulty: 'EASY', count: 2, pointsPerQuestion: 1, pinnedQuestionIds: ['a', 'b'] })
  assert.deepEqual(req.rows[1].pinnedQuestionIds, ['c'])
  assert.deepEqual(req.excludeQuestionIds, ['z'])
  assert.equal('seed' in req, false)
})

test('regenerate one row pins the others; swap pins the rest of the row and drops the swapped id', () => {
  assert.deepEqual(m.pinsFrom(preview, { skipRow: 0 }), { 1: ['c'] })
  assert.deepEqual(m.pinsFrom(preview, { dropId: 'a' }), { 0: ['b'], 1: ['c'] })
})

test('remove without replacement lowers the row count, and removes an emptied row with re-indexed pins', () => {
  const r1 = m.removeFromRow([row(), row({ count: 1 })], preview, 0, 'a')
  assert.equal(r1.rows[0].count, 1)
  assert.deepEqual(r1.pins, { 0: ['b'], 1: ['c'] })
  const r2 = m.removeFromRow([row({ count: 1 }), row({ count: 1 })], { rows: [{ rowIndex: 0, questions: [{ questionId: 'a' }] }, { rowIndex: 1, questions: [{ questionId: 'c' }] }] }, 0, 'a')
  assert.equal(r2.rows.length, 1)
  assert.deepEqual(r2.pins, { 0: ['c'] })
})

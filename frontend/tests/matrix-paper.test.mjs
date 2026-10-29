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
globalThis.localStorage ??= (() => { const m = new Map(); return { getItem: k => m.get(k) ?? null, setItem: (k, v) => m.set(k, String(v)), removeItem: k => m.delete(k) } })()
const mx = await import('../src/features/exams/matrixModel.ts')
const pm = await import('../src/features/exams/paperModel.ts')

const topics = [{ categoryId: 'a', name: 'Hàm số' }, { categoryId: 'b', name: 'Mũ – logarit' }, { categoryId: 'c', name: 'Tích phân' }]
const base = () => ({ ...mx.emptyMatrix(), topics })

test('matrix totals: counts per topic/level, points and share of score', () => {
  let m = base()
  m = mx.setCell(m, 'a', 'EASY', 4); m = mx.setCell(m, 'b', 'MEDIUM', 3); m = mx.setCell(m, 'c', 'HARD', 2); m = mx.setCell(m, 'a', 'HARD', -5)
  m = { ...m, points: { EASY: 0.25, MEDIUM: 0.5, HARD: 1 } }
  const t = mx.totals(m)
  assert.deepEqual(t.perLevel, { EASY: 4, MEDIUM: 3, HARD: 2 })
  assert.equal(t.questions, 9); assert.equal(t.perTopic.a, 4)
  assert.equal(t.points, 4.5)
  assert.deepEqual(t.percentPerLevel, { EASY: 22.2, MEDIUM: 33.3, HARD: 44.4 })
})

test('matrix becomes one blueprint row per non-empty cell, labelled by topic and level', () => {
  let m = { ...base(), questionType: 'MULTIPLE_CHOICE' }
  m = mx.setCell(m, 'a', 'EASY', 4); m = mx.setCell(m, 'c', 'HARD', 2)
  const rows = mx.toBlueprintRows(m, l => l)
  assert.equal(rows.length, 2)
  assert.deepEqual({ ...rows[1], key: undefined }, { key: undefined, categoryId: 'c', difficulty: 'HARD', count: 2, pointsPerQuestion: 0.25, questionType: 'MULTIPLE_CHOICE', label: 'Tích phân · HARD' })
})

test('matrix problems block only what the server would refuse; shortages are reported per cell', () => {
  assert.equal(mx.matrixProblem(mx.emptyMatrix())?.kind, 'NO_TOPICS')
  assert.equal(mx.matrixProblem(base())?.kind, 'EMPTY')
  let m = mx.setCell(base(), 'a', 'EASY', 150)
  assert.equal(mx.matrixProblem(m, 60)?.kind, 'TOO_MANY')
  assert.equal(mx.matrixProblem({ ...m, points: { EASY: 0, MEDIUM: 1, HARD: 1 } })?.kind, 'BAD_POINTS')
  assert.equal(mx.matrixProblem(m), null)
  assert.deepEqual(mx.shortages(m, { a: { EASY: 100 } }), [['a', 'EASY', 50]])
})

test('auto fill splits the total by ratio, evenly over topics, never above availability', () => {
  const a = { a: { EASY: 10, MEDIUM: 10, HARD: 1 }, b: { EASY: 10, MEDIUM: 10, HARD: 0 }, c: { EASY: 10, MEDIUM: 10, HARD: 2 } }
  const { matrix, unplaced } = mx.distribute(base(), 20, { EASY: 40, MEDIUM: 30, HARD: 30 }, a)
  const t = mx.totals(matrix)
  assert.deepEqual(t.perLevel, { EASY: 8, MEDIUM: 6, HARD: 3 })
  assert.equal(unplaced, 3) // 6 hard asked, only 3 exist
  assert.deepEqual([matrix.counts.a.EASY, matrix.counts.b.EASY, matrix.counts.c.EASY], [3, 3, 2])
  assert.equal(matrix.counts.b.HARD, 0)
  const free = mx.distribute(base(), 10, { EASY: 50, MEDIUM: 30, HARD: 20 }, undefined)
  assert.equal(mx.totals(free.matrix).questions, 10); assert.equal(free.unplaced, 0)
})

test('matrix templates are kept per teacher in this browser', () => {
  mx.saveTemplate('u1', { name: 'Giữa kỳ', savedAt: 'x', matrix: base() })
  mx.saveTemplate('u1', { name: 'Cuối kỳ', savedAt: 'y', matrix: base() })
  mx.saveTemplate('u1', { name: 'Giữa kỳ', savedAt: 'z', matrix: base() })
  assert.deepEqual(mx.loadTemplates('u1').map(x => [x.name, x.savedAt]), [['Giữa kỳ', 'z'], ['Cuối kỳ', 'y']])
  assert.deepEqual(mx.loadTemplates('u2'), [])
  assert.deepEqual(mx.deleteTemplate('u1', 'Cuối kỳ').map(x => x.name), ['Giữa kỳ'])
})

const q = (i, type = 'MULTIPLE_CHOICE') => ({
  questionId: 'q' + i, questionVersion: 1, questionType: type, content: 'Câu hỏi ' + i, points: 0.5, position: i, expectedAnswer: type === 'SHORT_ANSWER' ? '42' : null, explanation: null,
  options: type === 'TRUE_FALSE' ? [{ id: 't', content: 'Đúng', displayOrder: 0, correct: false }, { id: 'f', content: 'Sai', displayOrder: 1, correct: true }]
    : type === 'MULTIPLE_CHOICE' ? ['A', 'B', 'C', 'D'].map((c, k) => ({ id: `${i}${c}`, content: `Đáp án ${c}${i}`, displayOrder: k, correct: k === i % 4 })) : [],
})

test('exam codes: deterministic per code, different between codes, keys follow the shuffled options', () => {
  const qs = [...Array.from({ length: 12 }, (_, i) => q(i)), q(12, 'TRUE_FALSE'), q(13, 'SHORT_ANSWER'), q(14, 'ESSAY')]
  const opts = { codes: 3, firstCode: 101, shuffleQuestions: true, shuffleOptions: true }
  const [v1, v2, v3] = pm.makeVersions('exam-1', qs, opts)
  assert.deepEqual([v1.code, v2.code, v3.code], ['101', '102', '103'])
  assert.deepEqual(pm.makeVersions('exam-1', qs, opts)[1], v2, 'same code, same paper')
  assert.notDeepEqual(v1.items.map(i => i.question.questionId), v2.items.map(i => i.question.questionId))
  for (const v of [v1, v2, v3]) {
    assert.equal(v.items.length, 15)
    assert.deepEqual(new Set(v.items.map(i => i.question.questionId)).size, 15)
    for (const it of v.items) {
      if (it.question.questionType === 'MULTIPLE_CHOICE') {
        const correct = it.question.options.find(o => o.correct).content
        assert.equal(it.options.find(o => o.letter === it.key).content, correct)
      }
    }
    assert.equal(v.items.find(i => i.question.questionType === 'TRUE_FALSE').key, 'Sai')
    assert.equal(v.items.find(i => i.question.questionType === 'SHORT_ANSWER').key, '42')
    assert.equal(v.items.find(i => i.question.questionType === 'ESSAY').key, 'TL')
  }
  const plain = pm.makeVersions('exam-1', qs, { ...opts, codes: 1, shuffleQuestions: false, shuffleOptions: false })[0]
  assert.deepEqual(plain.items.map(i => i.question.questionId), qs.map(x => x.questionId))
  assert.equal(plain.items[1].key, 'B')
  assert.equal(pm.makeVersions('e', qs, { ...opts, codes: 99 }).length, 24)
})

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
const cm = await import('../src/components/charts/chartModel.ts')
const pm = await import('../src/features/progress/progressModel.ts')
const am = await import('../src/features/admin/adminStatsModel.ts')
const an = await import('../src/features/exams/analyticsModel.ts')

test('nice axis scales and evenly spread points', () => {
  assert.deepEqual(cm.niceScale(100), { max: 100, ticks: [0, 25, 50, 75, 100] })
  assert.deepEqual(cm.niceScale(7), { max: 8, ticks: [0, 2, 4, 6, 8] })
  assert.deepEqual(cm.niceScale(0), { max: 1, ticks: [0, 0.25, 0.5, 0.75, 1] })
  assert.equal(cm.niceScale(1234).max, 1500)
  assert.deepEqual(cm.xPositions(3, 0, 100), [0, 50, 100])
  assert.deepEqual(cm.xPositions(1, 0, 100), [50])
  assert.equal(cm.nearestIndex([0, 50, 100], 70), 1)
  const show = cm.labelEvery(30, 7)
  const shown = Array.from({ length: 30 }, (_, i) => i).filter(show)
  assert.ok(shown.length <= 8 && shown[0] === 0 && shown.at(-1) === 29)
  assert.equal(cm.dayLabel('2026-10-05'), '5/10')
})

test('progress timeline keeps valid scored results in time order, at most 20', () => {
  const it = (i, extra = {}) => ({ sessionId: 's' + i, examId: 'e' + (i % 2), title: 'Đề ' + i, attemptNumber: 1, phase: 'PUBLISHED', submittedAt: `2026-10-${String(10 + i).padStart(2, '0')}T01:00:00Z`, releasedAt: null, score: 5, maxScore: 10, percent: 50 + i, invalidated: false, ...extra })
  const items = [it(3), it(1), it(2, { invalidated: true }), it(4, { percent: null }), it(5)]
  assert.deepEqual(pm.scoredTimeline(items).map(i => i.sessionId), ['s1', 's3', 's5'])
  assert.equal(pm.scoredTimeline(Array.from({ length: 25 }, (_, i) => it(i % 20))).length, 20)
  assert.equal(pm.pointLabel(it(1), false), '11/10')
  assert.equal(pm.pointLabel(it(1, { attemptNumber: 2 }), true), '11/10 #2')
  assert.deepEqual([12, 5, 0, -4.9, -5, null].map(pm.trendOf), ['UP', 'UP', 'FLAT', 'FLAT', 'DOWN', 'NONE'])
})

test('admin highlights compare weeks, flag backlogs and report AI acceptance', () => {
  assert.deepEqual(am.lastVsPrevious([1, 1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 2, 2, 2]), { last: 14, previous: 7, change: 100 })
  assert.equal(am.lastVsPrevious([0, 3]).change, null)
  assert.deepEqual(am.rowsOf({ A: 1, B: 3 }, { B: 'Bê' }), [{ label: 'Bê', value: 3 }, { label: 'A', value: 1 }])
  const h = am.highlights({ attemptsPerDay: [...Array(7).fill(10), ...Array(7).fill(5)], usersNew: 3, locked: 0, needsReview: 40, submissions: 100,
    aiGenerated: 50, aiImported: 30, aiJobs: 10, aiFailed: 3, questionsTotal: 200, aiQuestions: 30 })
  assert.deepEqual(h.map(x => x.key), ['ATTEMPTS_DOWN', 'BACKLOG', 'AI_ACCEPT', 'AI_FAIL', 'AI_SHARE', 'NEW_USERS'])
  assert.equal(h[0].vars[0], 50); assert.equal(h[1].tone, 'warn'); assert.equal(h[2].vars[0], 60)
  assert.deepEqual([0.45, 0.25, 0.1, -0.2, null, undefined].map(an.discriminationOf), ['GOOD', 'FAIR', 'POOR', 'NEGATIVE', 'UNKNOWN', 'UNKNOWN'])
})

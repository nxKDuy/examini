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
const m = await import('../src/features/exams/analyticsModel.ts')

test('results pagination has one page for an empty result set', () => {
  assert.equal(m.resultPageCount(0, 20), 1)
  assert.equal(m.resultPageCount(21, 20), 2)
  assert.equal(m.resultPageCount(-3, 20), 1)
})

const sum = { examId: 'e', candidates: 3, submissions: 4, graded: 2, needsReview: 1, pending: 1, submissionRate: null, eligibleCount: null, invalidatedCount: 0, gradingCompletionRate: 0.5 }

test('headline KPIs keep the locked order and never invent null values', () => {
  const k = m.headlineKpis(sum)
  assert.deepEqual(k.map(x => x.key), ['students', 'completion', 'published', 'review'])
  assert.ok(k.every(x => x.value === null && x.reason), 'missing kpi block never shows 0')
})

test('headline KPIs read summary.kpi with server null reasons', () => {
  const kpi = { participatedStudents: null, submittedStudents: 3, registeredCount: 5, eligibleCount: 4, completionRate: 0.75,
    completionRateDenominator: 'eligibleCount', publishedAverageScore: null, needsGradingCount: 0,
    unavailable: { participatedStudents: 'no start event', publishedAverageScore: 'no release snapshot' } }
  const k = m.headlineKpis({ ...sum, kpi })
  assert.equal(k[0].value, null)
  assert.equal(k[0].reason, 'no start event')
  assert.equal(k[1].value, '75.0%')
  assert.equal(k[1].detail, '3 đã nộp hợp lệ / 4 đủ điều kiện')
  assert.equal(k[2].reason, 'no release snapshot')
  assert.equal(k[3].value, '0')
  assert.equal(m.headlineKpis({ ...sum, kpi: { ...kpi, publishedAverageScore: 7.5 } })[2].value, '7.5')
})

test('rates format as percent and null stays null', () => {
  assert.equal(m.percent(0.5), '50.0%')
  assert.equal(m.percent(null), null)
  assert.equal(m.attemptMetrics(sum)[2].value, '50.0%')
})

test('student filter keys on latest grading status', () => {
  const rows = [{ latestGradingStatus: 'GRADED' }, { latestGradingStatus: 'NEEDS_REVIEW' }]
  assert.equal(m.filterStudents(rows, '').length, 2)
  assert.equal(m.filterStudents(rows, 'GRADED').length, 1)
})

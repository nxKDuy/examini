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
const m = await import('../src/features/banks/copySyncModel.ts')

const field = (f, o = {}) => ({ field: f, sourceChanged: true, localChanged: false, conflict: false, redacted: false, reference: 'a', local: 'a', latest: 'b', ...o })
const q = { questionId: 'q', localVersion: 2, referenceSourceVersion: 1, cursorRevision: 0, latestSourceVersion: 3, availability: 'AVAILABLE', localStatus: 'ACTIVE',
  changes: [field('CONTENT'), field('DIFFICULTY', { sourceChanged: false }), field('OPTIONS', { redacted: true, localChanged: true, conflict: true, reference: null, local: null, latest: null })] }

test('only source changes become rows, conflicts first, nothing pre-selected', () => {
  const rows = m.syncRows(q)
  assert.deepEqual(rows.map(r => r.key), ['OPTIONS', 'CONTENT'])
  assert.equal(m.canApply(rows, {}), false)
  assert.equal(m.canApply(rows, { CONTENT: 'TAKE_SOURCE' }), false, 'conflict must be chosen')
  assert.equal(m.canApply(rows, { OPTIONS: 'KEEP_LOCAL' }), true)
})

test('unavailable source has no decidable rows', () => {
  assert.deepEqual(m.syncRows({ ...q, availability: 'UNAVAILABLE', latestSourceVersion: null }), [])
})

test('apply body carries the rendered expected versions and drops stale choices', () => {
  const body = m.applyBody(q, m.syncRows(q), { OPTIONS: 'KEEP_LOCAL', DIFFICULTY: 'TAKE_SOURCE' })
  assert.deepEqual(body, { expectedLocalVersion: 2, expectedReferenceSourceVersion: 1, expectedCursorRevision: 0, expectedSourceVersion: 3, decisions: { OPTIONS: 'KEEP_LOCAL' } })
  assert.equal(m.tookSource(body.decisions), false)
})

test('set rows split into info, items and order tabs with per-tab unresolved counts', () => {
  const set = { questionSetId: 's', localVersion: 1, referenceSourceVersion: 1, cursorRevision: 0, latestSourceVersion: 2, availability: 'AVAILABLE',
    metadataChanges: [field('TITLE', { conflict: true, localChanged: true })], sourceReordered: true, localReordered: true, reorderConflict: true,
    itemChanges: [{ sourceQuestionId: 'x', kind: 'ADDED', conflict: false }, { sourceQuestionId: 'y', kind: 'UPDATED', conflict: true }] }
  const rows = m.syncRows(set)
  assert.deepEqual(rows.map(r => r.key), ['TITLE', 'ITEM:y', 'ITEM:x', 'ORDER'])
  assert.deepEqual(m.unresolvedByTab(rows, { 'ITEM:y': 'KEEP_LOCAL' }), { info: 1, items: 0, order: 1 })
  assert.equal(m.canApply(rows, { TITLE: 'TAKE_SOURCE', 'ITEM:y': 'KEEP_LOCAL', ORDER: 'KEEP_LOCAL' }), true)
})

test('history actions parse the provider map text', () => {
  assert.deepEqual(m.parseActions('{CONTENT=KEEP_LOCAL, ITEM:abc=TAKE_SOURCE}'), [{ key: 'CONTENT', choice: 'KEEP_LOCAL' }, { key: 'ITEM:abc', choice: 'TAKE_SOURCE' }])
  assert.equal(m.decisionLabel('ORDER'), 'Thứ tự câu')
})

test('comparison values use labels and never invent a category name', () => {
  assert.equal(m.syncValue('DIFFICULTY', 'HARD'), 'Khó')
  assert.equal(m.syncValue('CATEGORY', '12345678-aaaa'), 'Chủ đề mã 12345678')
  assert.equal(m.syncValue('CONTENT', null), null)
})

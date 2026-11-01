import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'

const src = ts.transpileModule(readFileSync('src/features/question-bank/bulkActivate.ts', 'utf8'), { compilerOptions: { module: ts.ModuleKind.ESNext } }).outputText
const { activateMany, canActivate } = await import('data:text/javascript,' + encodeURIComponent(src))

test('only the actor\'s own drafts can be bulk-activated', () => {
  assert.equal(canActivate({ status: 'DRAFT', createdBy: 'u1' }, 'u1'), true)
  assert.equal(canActivate({ status: 'ACTIVE', createdBy: 'u1' }, 'u1'), false)
  assert.equal(canActivate({ status: 'DRAFT', createdBy: 'u2' }, 'u1'), false)
  assert.equal(canActivate({ status: 'DRAFT', createdBy: 'u1' }, undefined), false)
})

test('bulk activation keeps going after a failure, in order, with each question\'s own version', async () => {
  const calls = [], progress = []
  const qs = [{ id: 'a', questionVersion: 1, content: 'A' }, { id: 'b', questionVersion: 3, content: 'B' }, { id: 'c', questionVersion: 2, content: 'C' }]
  const r = await activateMany(qs, async (id, v) => { calls.push(`${id}@${v}`); if (id === 'b') throw new Error('Cần đúng 1 đáp án đúng') }, e => e.message, n => progress.push(n))
  assert.deepEqual(calls, ['a@1', 'b@3', 'c@2'])
  assert.deepEqual(r.activated, ['a', 'c'])
  assert.deepEqual(r.failed, [{ id: 'b', content: 'B', reason: 'Cần đúng 1 đáp án đúng' }])
  assert.deepEqual(progress, [1, 2, 3])
})

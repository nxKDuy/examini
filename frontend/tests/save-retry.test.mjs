import { test } from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import ts from 'typescript'

const src = ts.transpileModule(readFileSync(new URL('../src/features/exams/saveRetry.ts', import.meta.url), 'utf8'), { compilerOptions: { module: ts.ModuleKind.ESNext } }).outputText
const m = await import('data:text/javascript,' + encodeURIComponent(src))

test('only network, timeout, rate-limit and server errors are retried', () => {
  for (const s of [undefined, 0, 408, 429, 500, 502, 503, 504]) assert.equal(m.isTransientSaveError(s), true, String(s))
  for (const s of [400, 401, 403, 404, 409, 413, 422]) assert.equal(m.isTransientSaveError(s), false, String(s))
})

test('retry delay backs off and stays jittered within bounds', () => {
  assert.equal(m.saveRetryDelay(0, () => 0.5), 800)
  assert.equal(m.saveRetryDelay(1, () => 0.5), 1600)
  assert.equal(m.saveRetryDelay(2, () => 0.5), 3200)
  assert.ok(m.saveRetryDelay(0, () => 0) >= 680 && m.saveRetryDelay(0, () => 1) <= 920)
  assert.equal(m.SAVE_RETRIES, 3)
})

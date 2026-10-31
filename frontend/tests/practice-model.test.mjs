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
const pm = await import('../src/features/exams/practiceModel.ts')

const check = (o) => ({ questionId: 'q', share: 0, correct: false, correctAnswers: [], correctOptionIds: [], parts: {}, explanation: null, ...o })

test('practice verdicts: right, partial, wrong', () => {
  assert.equal(pm.verdictOf(check({ share: 1, correct: true })), 'right')
  assert.equal(pm.verdictOf(check({ share: 0.25 })), 'partial')
  assert.equal(pm.verdictOf(check({})), 'wrong')
  assert.ok(pm.CHECKABLE.includes('MATCHING'))
  assert.ok(!pm.CHECKABLE.includes('ESSAY') && !pm.CHECKABLE.includes('SPEAKING'))
})

test('practice part states mark the key, the wrong pick and each gap or pair', () => {
  assert.deepEqual(pm.partStates(check({ correctOptionIds: ['a'] }), ['b']), { a: 'right', b: 'wrong' })
  assert.deepEqual(pm.partStates(check({ correctOptionIds: ['a'] }), ['a']), { a: 'right' })
  assert.deepEqual(pm.partStates(check({ parts: { g1: true, g2: false } }), []), { g1: 'right', g2: 'wrong' })
  assert.deepEqual(pm.partStates(undefined, ['a']), {})
})

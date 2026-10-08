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
const f = await import('../src/features/grading/gradingFlow.ts')
const loc = (pathname, search = '') => ({ pathname, search })

test('navigation is blocked only when dirty AND the destination differs (sidebar, Link, Back and in-page URL switches alike)', () => {
  const here = loc('/grading', '?examId=e1&r=a')
  assert.equal(f.shouldBlockNavigation(false, here, loc('/dashboard')), false)
  assert.equal(f.shouldBlockNavigation(true, here, loc('/dashboard')), true) // sidebar / Link / Back to another page
  assert.equal(f.shouldBlockNavigation(true, here, loc('/grading', '?examId=e2')), true) // another exam
  assert.equal(f.shouldBlockNavigation(true, here, loc('/grading', '?examId=e1&r=b')), true) // another submission
  assert.equal(f.shouldBlockNavigation(true, here, loc('/grading', '?examId=e1&status=GRADED')), true) // a filter
  assert.equal(f.shouldBlockNavigation(true, here, loc('/grading', '?examId=e1&r=a')), false) // same place: nothing to lose
})

test('deferred guard: clean state runs the action at once', () => {
  const g = f.createDeferredGuard(); const ran = []
  assert.equal(g.request(() => ran.push('p3'), false), true); assert.deepEqual(ran, ['p3']); assert.equal(g.waiting, false)
})

test('deferred guard: dirty state holds the full action; cancel drops it and runs nothing; confirm runs exactly that action once', () => {
  const g = f.createDeferredGuard(); const ran = []
  assert.equal(g.request(() => ran.push('page 3 + close'), true), false)
  assert.equal(g.waiting, true); assert.deepEqual(ran, [])
  g.cancel(); assert.equal(g.waiting, false); g.confirm(); assert.deepEqual(ran, []) // cancelled: confirm has nothing to run
  g.request(() => ran.push('page 3 + close'), true); g.confirm(); g.confirm()
  assert.deepEqual(ran, ['page 3 + close']); assert.equal(g.waiting, false)
  g.request(() => ran.push('first'), true); g.request(() => ran.push('second'), true); g.confirm() // a newer request replaces the older one
  assert.deepEqual(ran, ['page 3 + close', 'second'])
})

const detail = rev => ({ id: 'r1', resultRevision: rev, items: [] })

test('finalize with a dirty draft saves, adopts the saved revision, then finalizes THAT revision', async () => {
  const calls = []
  const out = await f.finalizeWithDraft({ dirty: true, currentRevision: 4,
    saveDraft: async () => { calls.push('save'); return detail(5) }, adopt: async d => { calls.push(`adopt:${d.resultRevision}`) }, finalize: async rev => { calls.push(`finalize:${rev}`); return detail(6) } })
  assert.deepEqual(calls, ['save', 'adopt:5', 'finalize:5']); assert.equal(out.resultRevision, 6)
})

test('finalize without unsaved edits uses the current revision and does not save', async () => {
  const calls = []
  await f.finalizeWithDraft({ dirty: false, currentRevision: 4, saveDraft: async () => { calls.push('save'); return detail(9) }, adopt: async () => { calls.push('adopt') }, finalize: async rev => { calls.push(`finalize:${rev}`); return detail(5) } })
  assert.deepEqual(calls, ['finalize:4'])
})

test('save succeeds but finalize fails: the saved revision is already adopted, the error says the draft was saved, and the retry uses the new revision', async () => {
  const server = { revision: 4 }; const adopted = []; let failFinalize = true
  const attempt = dirty => f.finalizeWithDraft({ dirty, currentRevision: adopted.at(-1) ?? server.revision,
    saveDraft: async () => { server.revision = 5; return detail(5) }, adopt: async d => { adopted.push(d.resultRevision) },
    finalize: async rev => { if (rev !== server.revision) throw Object.assign(new Error('409'), { stale: true }); if (failFinalize) throw new Error('503'); return detail(6) } })
  await assert.rejects(attempt(true), e => e.message === '503' && e.draftSaved === true)
  assert.deepEqual(adopted, [5])
  failFinalize = false
  const ok = await attempt(false) // nothing unsaved any more; it must NOT go back to revision 4 (which would 409)
  assert.equal(ok.resultRevision, 6)
})

test('a failure while saving the draft is not reported as "draft saved"', async () => {
  await assert.rejects(f.finalizeWithDraft({ dirty: true, currentRevision: 4, saveDraft: async () => { throw new Error('400') }, adopt: async () => {}, finalize: async () => detail(5) }), e => e.message === '400' && e.draftSaved === undefined)
})

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
const m = await import('../src/features/participants/participantModel.ts')
const uuid = n => `3f2b8c1e-1111-4222-8333-${String(n).padStart(12, '0')}`

test('batch input: one kind per request, 1..100, exactly as typed, duplicates collapsed', () => {
  const ids = m.parseBatch(`${uuid(1)}\n${uuid(2)}, ${uuid(1)}`)
  assert.equal(ids.ok, true); assert.deepEqual(ids.request, { userIds: [uuid(1), uuid(2)] }); assert.equal(ids.duplicates, 1); assert.equal(ids.count, 2)
  const emails = m.parseBatch('Hoc.Sinh@Example.com\nb@x.vn')
  assert.deepEqual(emails.request, { emails: ['Hoc.Sinh@Example.com', 'b@x.vn'] }) // no lowercasing
  assert.equal(m.parseBatch(`${uuid(1)}\na@b.co`).ok, false) // mixed kinds
  assert.match(m.parseBatch('abc').error, /không phải mã người dùng/); assert.equal(m.parseBatch('   \n ').ok, false)
})

test('batch size limit is 100 distinct entries', () => {
  const many = n => Array.from({ length: n }, (_, i) => uuid(i + 1)).join('\n')
  assert.equal(m.parseBatch(many(100)).ok, true); assert.equal(m.parseBatch(many(101)).ok, false)
  assert.equal(m.parseBatch(many(100) + '\n' + uuid(5)).ok, true) // a duplicate does not count twice
})

test('result outcomes have Vietnamese labels for all six values; retry keeps only the rows that need fixing', () => {
  for (const o of ['ASSIGNED', 'REINSTATED', 'ALREADY_PARTICIPANT', 'CAPACITY_FULL', 'NOT_FOUND', 'NOT_A_STUDENT']) assert.ok(m.OUTCOME_LABEL[o].text, o)
  const results = [{ input: 'a', outcome: 'ASSIGNED' }, { input: 'b', outcome: 'NOT_FOUND' }, { input: 'c', outcome: 'ALREADY_PARTICIPANT' }, { input: 'd', outcome: 'CAPACITY_FULL' }, { input: 'e', outcome: 'NOT_A_STUDENT' }, { input: 'f', outcome: 'REINSTATED' }]
  assert.equal(m.retryInput(results), 'b\nd\ne'); assert.deepEqual(m.outcomeSummary(results), { added: 2, existing: 1, failed: 3 })
})

test('who may add and who may act: only OWNER/MANAGER write; adding stops at CLOSED; PROCTOR is read-only', () => {
  for (const s of ['DRAFT', 'PUBLISHED', 'OPEN']) assert.equal(m.canAddParticipants(true, s), true, s)
  for (const s of ['CLOSED', 'COMPLETED']) assert.equal(m.canAddParticipants(true, s), false, s)
  assert.equal(m.canAddParticipants(false, 'OPEN'), false)
  assert.deepEqual(m.rowAbilities(true, { status: 'ACTIVE' }), { revoke: true, grant: true, invalidate: true })
  assert.deepEqual(m.rowAbilities(true, { status: 'REVOKED' }), { revoke: false, grant: false, invalidate: true })
  assert.deepEqual(m.rowAbilities(false, { status: 'ACTIVE' }), { revoke: false, grant: false, invalidate: false })
})

test('invalidation needs a public reason plus the attempt number or the Student name typed back', () => {
  assert.equal(m.invalidateReady('Gian lận', '2', 2, 'Lan'), true); assert.equal(m.invalidateReady('Gian lận', 'Lan', 2, 'Lan'), true)
  assert.equal(m.invalidateReady('', '2', 2, 'Lan'), false); assert.equal(m.invalidateReady('  ', '2', 2, 'Lan'), false)
  assert.equal(m.invalidateReady('x', '3', 2, 'Lan'), false); assert.equal(m.invalidateReady('x', '', 2, ''), false)
})

test('grant quantity is an integer 1..100; request ids differ between dialog openings', () => {
  for (const ok of ['1', '100', '7']) assert.equal(m.grantQuantityError(ok), null, ok)
  for (const bad of ['0', '101', '1.5', '', 'abc', '-1']) assert.ok(m.grantQuantityError(bad), bad)
  assert.notEqual(m.newRequestId(), m.newRequestId())
})

test('names fall back to a short id; attempt summary uses the server effective limit and shows invalidated attempts', () => {
  assert.deepEqual(m.participantName({ displayName: ' Lan ', userId: uuid(1) }), { text: 'Lan', fallback: false })
  assert.deepEqual(m.participantName({ userId: uuid(1) }), { text: 'Thí sinh 3f2b8c1e', fallback: true })
  assert.equal(m.attemptSummary({ effectiveAttemptLimit: 3, attempts: { used: 2, valid: 2, invalidated: 0, remaining: 1, active: false } }), '2/3')
  assert.equal(m.attemptSummary({ effectiveAttemptLimit: 3, attempts: { used: 2, valid: 1, invalidated: 1, remaining: 1, active: false } }), '2/3 (1 bị vô hiệu)')
  assert.equal(m.attemptSummary({ effectiveAttemptLimit: 1 }), 'Chưa làm / 1')
})

test('attempt links: only a live attempt resumes; only a submitted one links to its result; invalidated shows the public reason', () => {
  assert.equal(m.resumable({ status: 'IN_PROGRESS', invalidated: false }), true); assert.equal(m.resumable({ status: 'IN_PROGRESS', invalidated: true }), false)
  assert.equal(m.resumable({ status: 'SUBMITTED', invalidated: false }), false)
  assert.equal(m.resultLink({ sessionId: 's1', status: 'SUBMITTED' }), '/exam/result/s1'); assert.equal(m.resultLink({ sessionId: 's1', status: 'AUTO_SUBMITTED' }), '/exam/result/s1')
  assert.equal(m.resultLink({ sessionId: 's1', status: 'CANCELLED' }), null); assert.equal(m.resultLink({ sessionId: 's1', status: 'IN_PROGRESS' }), null)
  assert.equal(m.publicReason({ invalidationReason: ' Sai phòng thi ' }), 'Sai phòng thi'); assert.equal(m.publicReason({}), null)
})

test('RESULT_INVALIDATED is recognised only as a 409', () => {
  const err = (status, detail) => ({ response: { status, data: { detail } } })
  assert.equal(m.isInvalidatedConflict(err(409, 'RESULT_INVALIDATED')), true); assert.equal(m.isInvalidatedConflict(err(409, 'STALE_RESULT_REVISION')), false)
  assert.equal(m.isInvalidatedConflict(err(500, 'RESULT_INVALIDATED')), false)
})

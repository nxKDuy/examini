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
const m = await import('../src/features/grading/gradingModel.ts')

const item = (o = {}) => ({ questionId: 'q1', questionVersion: 1, position: 1, questionContent: 'Q', questionType: 'ESSAY', pointValue: 10, answerContent: 'a', selectedOptionContent: null,
  autoScore: null, manualScore: null, awardedPoints: null, needsReview: true, feedback: null, manualGradedBy: null, manualGradedAt: null, ...o })
const detail = (items, o = {}) => ({ id: 'r1', examId: 'e1', examTitle: 'E', studentId: '12345678-aaaa', attemptNumber: 1, status: 'NEEDS_REVIEW', score: null, totalScore: 20, publishedScore: null,
  pendingManualItemCount: 1, submittedAt: null, gradedAt: null, durationSeconds: null, feedback: null, resultRevision: 4, publishedRevision: null, items, ...o })
const mcq = (o = {}) => item({ questionId: 'q2', questionType: 'MULTIPLE_CHOICE', pointValue: 10, autoScore: 10, awardedPoints: 10, needsReview: false, ...o })

test('scores must be finite and within [0, pointValue]', () => {
  const i = item()
  assert.equal(m.scoreProblem(i, { score: '', feedback: '', overrideReason: '' }), null)
  assert.equal(m.scoreProblem(i, { score: '10', feedback: '', overrideReason: '' }), null)
  for (const bad of ['-1', '10.5', 'abc', 'Infinity', 'NaN']) assert.ok(m.scoreProblem(i, { score: bad, feedback: '', overrideReason: '' }), bad)
})

test('overriding an objective auto-score needs its own reason; matching the auto-score or an unchanged earlier override does not', () => {
  const d = detail([item(), mcq()]); const init = m.formFromDetail(d)
  const f = structuredClone(init); f.items.q2.score = '0'
  assert.equal(m.itemProblems(d, f, init).q2.reason, 'Nhập lý do khi sửa điểm tự động.')
  f.items.q2.overrideReason = '  Sai đáp án khóa  '
  assert.equal(m.itemProblems(d, f, init).q2, undefined)
  const same = structuredClone(init); same.items.q2.score = '10'
  assert.equal(m.itemProblems(d, same, init).q2, undefined) // equal to auto-score is not an override
  const essay = structuredClone(init); essay.items.q1.score = '7'
  assert.equal(m.itemProblems(d, essay, init).q1, undefined) // essays never need a reason
  const earlier = m.formFromDetail(detail([mcq({ manualScore: 3, awardedPoints: 3 })]))
  assert.equal(m.reasonRequired(mcq({ manualScore: 3 }), earlier.items.q2, earlier.items.q2), false)
})

test('draft payload: expectedRevision, unchanged scores null, changed scores with trimmed reason, empty feedback null, no keys leaked', () => {
  const d = detail([item(), mcq({ manualScore: 3, awardedPoints: 3 })]); const init = m.formFromDetail(d)
  const f = structuredClone(init); f.items.q1.score = '7.5'; f.items.q1.feedback = 'Tốt'; f.items.q2.feedback = ''; f.feedback = '  '
  const req = m.toDraftRequest(d, f, init)
  assert.equal(req.expectedRevision, 4); assert.equal(req.feedback, null)
  assert.deepEqual(req.items[0], { questionId: 'q1', score: 7.5, feedback: 'Tốt', overrideReason: null })
  assert.deepEqual(req.items[1], { questionId: 'q2', score: null, feedback: null, overrideReason: null })
  const g = structuredClone(init); g.items.q2.score = '0'; g.items.q2.overrideReason = ' lý do '
  assert.deepEqual(m.toDraftRequest(d, g, init).items[1], { questionId: 'q2', score: 0, feedback: null, overrideReason: 'lý do' })
})

test('dirty tracking, preview total and unresolved items', () => {
  const d = detail([item(), mcq()]); const init = m.formFromDetail(d)
  assert.equal(m.isDirty(init, init), false)
  const f = structuredClone(init); f.items.q1.score = '6'
  assert.equal(m.isDirty(f, init), true); assert.equal(m.previewTotal(d, f), 16); assert.equal(m.previewTotal(d, init), 10)
  assert.deepEqual(m.unresolvedItems(d, init).map(i => i.questionId), ['q1']); assert.equal(m.unresolvedItems(d, f).length, 0)
  const fb = structuredClone(init); fb.feedback = 'x'; assert.equal(m.isDirty(fb, init), true)
})

test('publication state separates graded from published, including a regrade in progress', () => {
  assert.equal(m.publicationOf({ publishedRevision: null, resultRevision: 3 }), 'NONE')
  assert.equal(m.publicationOf({ publishedRevision: 3, resultRevision: 3 }), 'CURRENT')
  assert.equal(m.publicationOf({ publishedRevision: 3, resultRevision: 5 }), 'OUTDATED')
  assert.equal(m.canRelease({ status: 'GRADED', publishedRevision: null, resultRevision: 3 }), true)
  assert.equal(m.canRelease({ status: 'GRADED', publishedRevision: 3, resultRevision: 3 }), false)
  assert.equal(m.canRelease({ status: 'PENDING', publishedRevision: 1, resultRevision: 3 }), false)
  assert.equal(m.canEdit({ status: 'GRADED' }), false); assert.equal(m.canRegrade({ status: 'GRADED' }), true); assert.equal(m.canEdit({ status: 'PENDING' }), true)
  assert.equal(m.scoreDelta(8, 6.5), 1.5); assert.equal(m.scoreDelta(null, 6), null)
})

test('stale revision is only a 409 with STALE_RESULT_REVISION', () => {
  assert.equal(m.isStaleRevision({ response: { status: 409, data: { detail: 'STALE_RESULT_REVISION' } } }), true)
  assert.equal(m.isStaleRevision({ response: { status: 409, data: { detail: 'Result is not finalized' } } }), false)
  assert.equal(m.isStaleRevision({ response: { status: 500, data: { detail: 'STALE_RESULT_REVISION' } } }), false)
  assert.equal(m.isStaleRevision(new Error('x')), false)
})

test('Student: statuses, no fake zero, UUID answers are not guessed, unreadable correct answers dropped', () => {
  for (const [s, p] of [['IN_PROGRESS', 'IN_PROGRESS'], ['SUBMITTED', 'WAITING'], ['GRADING', 'WAITING'], ['NEEDS_REVIEW', 'REVIEWING'], ['GRADED_UNRELEASED', 'UNRELEASED'], ['GRADED', 'PUBLISHED'], ['X', 'UNKNOWN']]) assert.equal(m.studentPhase(s), p)
  assert.equal(m.studentPolls('SUBMITTED'), 3000); assert.equal(m.studentPolls('NEEDS_REVIEW'), 30000); assert.equal(m.studentPolls('GRADED'), false); assert.equal(m.studentPolls('IN_PROGRESS'), false)
  const uuid = '3f2b8c1e-1111-4222-8333-444455556666'
  assert.deepEqual(m.studentAnswerText('MULTIPLE_CHOICE', uuid), { text: 'Đã chọn một phương án', unresolved: true })
  assert.deepEqual(m.studentAnswerText('MULTIPLE_CHOICE', uuid, 'Nội dung đáp án đã chọn'), { text: 'Nội dung đáp án đã chọn', unresolved: false })
  assert.deepEqual(m.studentAnswerText('MULTIPLE_CHOICE', uuid, null), { text: 'Đã chọn một phương án', unresolved: true })
  assert.deepEqual(m.studentAnswerText('ESSAY', uuid), { text: uuid, unresolved: false }) // free text is shown as written
  assert.deepEqual(m.studentAnswerText('MULTIPLE_CHOICE', 'Hà Nội'), { text: 'Hà Nội', unresolved: false })
  assert.equal(m.studentAnswerText('ESSAY', undefined), null)
  assert.deepEqual(m.readableAnswers([uuid, 'Hà Nội']), ['Hà Nội']); assert.deepEqual(m.readableAnswers(undefined), [])
})

test('grading queue uses trusted studentDisplayName and retains UUID fallback for legacy/provider failures', async () => {
  assert.equal(m.studentName({ studentId: '12345678-aaaa', studentDisplayName: '  Học sinh A  ' }), 'Học sinh A')
  assert.equal(m.studentName({ studentId: '12345678-aaaa', studentDisplayName: null }), 'Thí sinh 12345678')
  assert.equal(m.studentName({ studentId: '87654321-bbbb' }), 'Thí sinh 87654321')
})

const shortAnswer = (o = {}) => item({ questionId: 'q3', questionType: 'SHORT_ANSWER', pointValue: 5, autoScore: 5, awardedPoints: 5, needsReview: false, answerContent: 'Hà Nội', ...o })

test('SHORT_ANSWER is auto-graded (override needs a reason) but is NOT a choice answer (free text, never an option id)', () => {
  assert.equal(m.isAutoGraded('SHORT_ANSWER'), true); assert.equal(m.isChoiceAnswer('SHORT_ANSWER'), false)
  assert.equal(m.isAutoGraded('MULTIPLE_CHOICE'), true); assert.equal(m.isChoiceAnswer('TRUE_FALSE'), true)
  assert.equal(m.isAutoGraded('ESSAY'), false); assert.equal(m.isChoiceAnswer('ESSAY'), false)
  const d = detail([shortAnswer()]); const init = m.formFromDetail(d)
  const f = structuredClone(init); f.items.q3.score = '0'
  assert.equal(m.itemProblems(d, f, init).q3.reason, 'Nhập lý do khi sửa điểm tự động.')
  f.items.q3.overrideReason = 'Chấp nhận cách viết khác'
  assert.equal(m.itemProblems(d, f, init).q3, undefined)
  assert.deepEqual(m.toDraftRequest(d, f, init).items[0], { questionId: 'q3', score: 0, feedback: null, overrideReason: 'Chấp nhận cách viết khác' })
  // A short answer that looks like a UUID is the student's text, not an option to hide.
  const uuid = '3f2b8c1e-1111-4222-8333-444455556666'
  assert.deepEqual(m.studentAnswerText('SHORT_ANSWER', uuid), { text: uuid, unresolved: false })
})

test('after an objective/short-answer override was saved, later saves (other item score, any feedback) never resend a score or reason for it', () => {
  // The server now reports the saved override as manualScore; the reason is not echoed back.
  const d = detail([item(), mcq({ manualScore: 0, awardedPoints: 0 }), shortAnswer({ manualScore: 1, awardedPoints: 1 })], { status: 'PENDING', resultRevision: 7 })
  const init = m.formFromDetail(d); const f = structuredClone(init)
  f.items.q1.score = '6'; f.items.q2.feedback = 'Xem lại bài 3'; f.items.q3.feedback = 'Gần đúng'; f.feedback = 'Cố gắng'
  assert.deepEqual(m.itemProblems(d, f, init), {}) // no reason demanded again
  const req = m.toDraftRequest(d, f, init)
  assert.equal(req.expectedRevision, 7)
  assert.deepEqual(req.items.map(i => [i.questionId, i.score, i.overrideReason]), [['q1', 6, null], ['q2', null, null], ['q3', null, null]])
  for (const i of req.items.filter(x => x.questionId !== 'q1')) assert.equal(i.score, null) // never "score different from auto with a null reason"
  // Editing the overridden score again does demand a (new) reason.
  const again = structuredClone(init); again.items.q2.score = '4'
  assert.equal(m.itemProblems(d, again, init).q2.reason, 'Nhập lý do khi sửa điểm tự động.')
  // Typing the old value back is not a change either.
  const same = structuredClone(init); same.items.q2.score = '0'
  assert.equal(m.toDraftRequest(d, same, init).items[1].score, null)
})

test('an invalidated attempt can never be edited, finalized, released or regraded, whatever its grading state', () => {
  for (const status of ['NEEDS_REVIEW', 'PENDING', 'GRADED']) {
    const s = { status, invalidated: true, publishedRevision: null, resultRevision: 3 }
    assert.equal(m.canEdit(s), false, status); assert.equal(m.canRelease(s), false, status); assert.equal(m.canRegrade(s), false, status)
  }
  assert.equal(m.isInvalidated({ invalidated: true }), true); assert.equal(m.isInvalidated({}), false)
  assert.equal(m.canEdit({ status: 'PENDING', invalidated: false }), true); assert.equal(m.canRegrade({ status: 'GRADED' }), true) // valid attempts unchanged
})

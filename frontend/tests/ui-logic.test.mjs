import { test } from 'node:test'
import assert from 'node:assert/strict'
import { registerHooks } from 'node:module'
import { readFileSync, existsSync } from 'node:fs'
import ts from 'typescript'

// Same TS loader approach as auth-runtime.test.mjs: execute the real source modules without a new test dependency.
registerHooks({
  resolve(specifier, context, next) {
    if (specifier.startsWith('.') && context.parentURL?.includes('/src/')) {
      const url = new URL(specifier, context.parentURL)
      for (const ext of ['.ts', '.tsx']) if (existsSync(new URL(url.href + ext))) return { url: url.href + ext, shortCircuit: true }
    }
    return next(specifier, context)
  },
  load(url, context, next) {
    if (/\.tsx?$/.test(url)) {
      const source = readFileSync(new URL(url), 'utf8').replace('import.meta.env.VITE_API_BASE_URL', "'/'")
      return { format: 'module', shortCircuit: true, source: ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, jsx: ts.JsxEmit.ReactJSX } }).outputText }
    }
    return next(url, context)
  },
})
const storage = new Map()
globalThis.localStorage = { getItem: k => storage.get(k) ?? null, setItem: (k, v) => storage.set(k, v), removeItem: k => storage.delete(k) }
globalThis.window = { localStorage: globalThis.localStorage }

const { errorInfo } = await import('../src/lib/errors.ts')
const { parseUtc, fromLocalInput, toLocalInput, formatDuration, formatScore } = await import('../src/lib/format.ts')
const { policyError } = await import('../src/features/exams/policy.ts')
const { rememberAttempt, lastAttempt } = await import('../src/features/exams/attemptHints.ts')

const problem = (status, detail) => ({ response: { status, data: { detail } } })

test('known backend business errors become actionable messages', () => {
  assert.match(errorInfo(problem(409, 'Attempt limit reached')).message, /hết số lượt/)
  assert.match(errorInfo(problem(409, 'Exam is not OPEN')).message, /không mở/)
  const publish = errorInfo(problem(409, 'Question is missing, unapproved or unversioned: 0f2a6c1e-1111-4222-8333-944445555666'))
  assert.match(publish.message, /0f2a6c1e/)
  assert.match(publish.message, /điều kiện ngân hàng/)
  assert.match(errorInfo(problem(409, 'Kỳ thi hiện đã đạt giới hạn người đang làm bài. Vui lòng thử lại sau.')).message, /giới hạn người đang làm bài/)
  assert.match(errorInfo(problem(400, 'Invalid policy/capacity; platform limit 100')).message, /tối đa 100/)
  assert.match(errorInfo(problem(403, 'Required service rejected request or unavailable')).message, /không có quyền/)
  assert.equal(errorInfo(problem(409, 'Required service rejected this request or is unavailable')).detail, 'Required service rejected this request or is unavailable')
  assert.match(errorInfo(problem(503, 'Required service rejected this request or is unavailable')).message, /không khả dụng/)
})

test('unknown 4xx detail is shown verbatim; status, network and 5xx fall back safely', () => {
  assert.equal(errorInfo(problem(409, 'Chỉ PENDING_REVIEW được quyết định duyệt')).message, 'Chỉ PENDING_REVIEW được quyết định duyệt')
  assert.match(errorInfo({ response: { status: 403, data: {} } }).message, /không có quyền/)
  assert.equal(errorInfo(new Error('Network Error')).status, 0)
  assert.doesNotMatch(errorInfo(problem(500, 'NullPointerException at Foo.java')).message, /NullPointer/)
  assert.deepEqual(errorInfo({ response: { status: 400, data: { detail: 'Dữ liệu không hợp lệ', errors: { email: 'Email không hợp lệ' } } } }).fields, { email: 'Email không hợp lệ' })
})

test('exam schedule round-trips between local input and server UTC LocalDateTime', () => {
  const utc = fromLocalInput('2026-10-05T09:30')
  assert.match(utc, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}$/)
  // Inputs are Vietnam wall time (UTC+7) whatever the device zone is.
  assert.equal(utc, '2026-10-05T02:30:00')
  assert.equal(new Date(`${utc}Z`).getTime(), new Date('2026-10-05T09:30+07:00').getTime())
  assert.equal(toLocalInput(utc), '2026-10-05T09:30')
  assert.equal(parseUtc('2026-10-05T08:00:00').toISOString(), '2026-10-05T08:00:00.000Z')
  assert.equal(parseUtc('2026-10-05T08:00:00Z').toISOString(), '2026-10-05T08:00:00.000Z')
  assert.equal(fromLocalInput(''), undefined)
})

test('duration and score formatting', () => {
  assert.equal(formatDuration(3725), '1:02:05')
  assert.equal(formatDuration(59.9), '00:59')
  assert.equal(formatDuration(-4), '00:00')
  assert.equal(formatScore(7.5), '7.5')
  assert.equal(formatScore(null), '-')
})

test('participation policy mirrors backend capacity rules', () => {
  const ok = { accessMode: 'PUBLIC', maxParticipants: 10, maxConcurrentSessions: 5, attemptLimit: 1 }
  assert.equal(policyError(ok), null)
  assert.match(policyError({ ...ok, maxConcurrentSessions: 11 }), /không được lớn hơn/)
  assert.match(policyError({ ...ok, attemptLimit: 0 }), /từ 1/)
  assert.match(policyError({ ...ok, maxParticipants: 500, maxConcurrentSessions: 101 }), /tối đa là 100/)
  assert.match(policyError({ ...ok, accessMode: 'JOIN_CODE', joinCode: 'a b' }), /Mã tham gia/)
  assert.equal(policyError({ ...ok, accessMode: 'JOIN_CODE', joinCode: undefined }), null)
})

test('attempt hints are scoped per account', () => {
  rememberAttempt('student-a', 'exam-1', 'session-1')
  assert.equal(lastAttempt('student-a', 'exam-1'), 'session-1')
  assert.equal(lastAttempt('student-b', 'exam-1'), undefined)
  assert.equal(lastAttempt(undefined, 'exam-1'), undefined)
})

test('scheduling error codes become actionable Vietnamese messages', () => {
  assert.match(errorInfo(problem(409, 'SCHEDULE_REVISION_MISMATCH')).message, /tải lại lịch/)
  assert.match(errorInfo(problem(409, 'START_ADMISSION_IN_PROGRESS')).message, /thử lại/)
  assert.match(errorInfo(problem(409, 'OPEN_EXAM_END_AT_MUST_EXTEND')).message, /muộn hơn/)
  assert.match(errorInfo(problem(400, 'Reason required for early close')).message, /lý do/)
  assert.match(errorInfo(problem(400, 'startAt falls in a daylight-saving gap')).message, /chuyển giờ mùa hè/)
  assert.match(errorInfo(problem(409, 'SCHEDULE_LOCKED_AFTER_STUDENT_START')).message, /đã có thí sinh/i)
  assert.equal(errorInfo(problem(409, 'SCHEDULE_REVISION_MISMATCH')).detail, 'SCHEDULE_REVISION_MISMATCH')
})

test('Start/Join denials from the backend read as plain Vietnamese', () => {
  assert.match(errorInfo(problem(409, 'Join is not available before startAt')).message, /Chưa đến giờ bắt đầu/)
  assert.match(errorInfo(problem(409, 'EXAM_NOT_OPEN')).message, /chưa mở/)
  assert.match(errorInfo(problem(409, 'INSUFFICIENT_TIME')).message, /không đủ/)
  assert.match(errorInfo(problem(409, 'AFTER_END_AT')).message, /qua giờ kết thúc/)
})

test('legacy editor fields: stored schedule values pass through verbatim; create sends none', async () => {
  const { legacyScheduleFields } = await import('../src/features/exams/examInput.ts')
  assert.deepEqual(legacyScheduleFields(), {})
  assert.deepEqual(legacyScheduleFields({ startTime: '2030-01-02T07:00:00.123456', endTime: '2030-01-02T09:00:00' }), { startTime: '2030-01-02T07:00:00.123456', endTime: '2030-01-02T09:00:00' })
  const none = legacyScheduleFields({ startTime: null, endTime: null })
  assert.equal(none.startTime, null); assert.equal(none.endTime, null) // null is sent as null, never dropped
  assert.equal(JSON.stringify(none), '{"startTime":null,"endTime":null}')
})

test('import review: new and unknown flags are labelled and still need review', async () => {
  const m = await import('../src/features/question-import/importModel.ts')
  assert.equal(m.flagLabel('NUMBERING_GAP'), 'Số câu bị nhảy')
  assert.equal(m.flagLabel('SOMETHING_NEW'), 'Cần xem lại (SOMETHING_NEW)')
  const base = { reviewRequired: false, reviewed: false, excluded: false, importedQuestionId: null }
  assert.equal(m.mustReview({ ...base, flags: ['POSSIBLE_MERGED_QUESTIONS'] }), false, 'known flag: server decides')
  assert.equal(m.mustReview({ ...base, flags: ['SOMETHING_NEW'] }), true)
  assert.equal(m.mustReview({ ...base, flags: ['SOMETHING_NEW'], reviewed: true }), false)
})

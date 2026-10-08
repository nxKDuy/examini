// Local, in-memory API fixture for shared-shell browser checks; never an app backend.
import { createServer } from 'node:http'
import { bankFixture } from './bank-fixture.mjs'
createServer(async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', 'http://127.0.0.1:3107')
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization')
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, PATCH, DELETE, OPTIONS')
  res.setHeader('Content-Type', 'application/json')
  if (req.method === 'OPTIONS') { res.end(); return }
  let raw = ''
  for await (const chunk of req) raw += chunk
  const body = raw ? JSON.parse(raw) : {}
  const url = new URL(req.url, 'http://localhost')
  const identity = body.email?.split('@')[0] || req.headers.authorization?.replace('Bearer fixture-', '') || 'student'
  const bank = bankFixture(url, req.method, body, identity)
  if (bank) { res.statusCode = bank[0]; res.end(bank[0] === 204 ? undefined : JSON.stringify(bank[1])); return }
  let data
  if (['/auth/login', '/auth/register'].includes(url.pathname)) {
    const persona = body.persona?.toLowerCase() || identity
    data = { accessToken: `fixture-${persona}`, refreshToken: `refresh-${persona}` }
  } else if (url.pathname === '/auth/me') {
    const admin = identity === 'admin'
    data = { id: identity, email: `${identity}@example.test`, fullName: `${identity} Fixture`, role: admin ? 'ADMIN' : identity.toUpperCase(), persona: admin ? 'TEACHER' : identity.toUpperCase(), systemPrivilege: admin ? 'ADMIN' : 'NONE' }
  } else if (url.pathname === '/notifications/unread-count') data = { count: 0 }
  else if (url.pathname === '/notifications/push/public-key') data = { enabled: false, publicKey: null }
  else if (['/subjects', '/categories', '/exams/my', '/exams/public', '/exams/invitations'].includes(url.pathname)) data = []
  else if (url.pathname === '/auth/logout') data = {}
  // Organization fixtures (browser checks of the Organization UI only; the real contract is organization-api.md)
  else if (url.pathname.startsWith('/organizations')) {
    const teacher = identity !== 'student'
    const me = teacher ? { membershipId: 'm-owner', role: 'OWNER', canReview: false, capabilities: ['BANK_READ', 'BANK_USE', 'BANK_SUBMIT', 'BANK_REVIEW'] } : { membershipId: 'm-me', role: 'MEMBER', canReview: false, capabilities: ['BANK_READ'] }
    const org = { id: 'o1', name: 'Khoa CNTT', description: 'Tổ chức thử', status: 'ACTIVE', revision: 4, me }
    const mem = (membershipId, role, name, extra = {}) => ({ membershipId, userId: `${membershipId}-0000-0000-0000-000000000000`, ...(name ? { displayName: name } : {}), role, status: 'ACTIVE', canReview: false, capabilities: ['BANK_READ', 'BANK_USE', 'BANK_SUBMIT'], joinedAt: '2026-10-01T00:00:00Z', revision: 2, ...extra })
    const page = items => ({ items, page: 0, size: 20, totalElements: items.length, totalPages: 1 })
    const parts = url.pathname.split('/').filter(Boolean)
    if (url.pathname === '/organizations/invitations/mine') data = page(teacher ? [] : [{ id: 'i1', organizationId: 'o1', organizationName: 'Khoa CNTT', recipientUserId: 'student', role: 'MEMBER', status: 'PENDING', expiresAt: new Date(Date.now() + 6 * 864e5).toISOString(), revision: 1 }])
    else if (/\/invitations\/[^/]+\/accept$/.test(url.pathname)) data = mem('m-me', 'MEMBER', 'Student Fixture')
    else if (url.pathname === '/organizations') data = page([org])
    else if (parts.length === 2) data = org
    else if (parts[2] === 'members' && parts.length === 3) data = page([mem('m-owner', 'OWNER', 'Giáo viên Chủ'), mem('m-a', 'MEMBER', 'Học sinh A'), mem('m-stale', 'MEMBER', null)])
    else if (parts[2] === 'members' && req.method === 'PATCH') {
      if (parts[3] === 'm-stale') { res.statusCode = 409; res.end(JSON.stringify({ title: 'Conflict', status: 409, detail: 'Dữ liệu đã thay đổi (revision hiện tại 3)' })); return }
      data = mem(parts[3], body.role ?? 'MEMBER', 'Học sinh A', { canReview: body.canReview ?? false, revision: 3 })
    } else if (parts[2] === 'invitations') data = page([])
    else if (parts[2] === 'audit') data = page([{ id: 'a1', actorUserId: 'm-owner-0000', action: 'ORGANIZATION_CREATED', occurredAt: '2026-10-01T00:00:00Z' }])
    else data = page([])
  }
  // Participant/attempt fixtures (browser checks of the Participants UI only; the real contract is participant-attempt.md)
  else if (/^\/exams\/e1(\/|$)/.test(url.pathname) || /^\/sessions\//.test(url.pathname)) {
    const parts = url.pathname.split('/').filter(Boolean)
    const exam = { id: 'e1', code: 'ABC123', title: 'Kỳ thi thử', description: null, ownerUserId: 'teacher', type: 'EXAM', durationMinutes: 30, totalScore: 10, passingScore: null, questions: [], questionIds: [], shuffleQuestions: false, shuffleOptions: false, status: 'OPEN', accessMode: 'ASSIGNED', maxParticipants: 30, maxConcurrentSessions: 5, attemptLimit: 1, startTime: null, endTime: null, createdAt: '2026-10-01T00:00:00Z' }
    const counts = { registered: 3, eligible: 2, revoked: 1 }
    const row = (id, name, status, extra = {}) => ({ userId: `${id}-1111-2222-3333-444444444444`, ...(name ? { displayName: name } : {}), source: 'ASSIGNED', status, joinedAt: '2026-10-01T00:00:00Z', extraAttempts: 0, attemptLimit: 1, effectiveAttemptLimit: 1, attempts: { used: 1, valid: 1, invalidated: 0, remaining: 0, active: false }, ...extra })
    const att = (n, extra = {}) => ({ sessionId: `s${n}`, userId: 'x', attemptNumber: n, status: 'SUBMITTED', startedAt: '2026-10-05T01:00:00', invalidated: false, ...extra })
    const nowIso = new Date().toISOString()
    if (url.pathname === '/exams/e1') data = exam
    else if (parts[2] === 'members') data = {}
    else if (parts[2] === 'schedule') data = { examId: 'e1', startAt: null, endAt: null, timezone: 'UTC', deadlineRule: 'FULL_DURATION', autoOpen: false, autoClose: false, reminderOffsetsMinutes: [], scheduleRevision: 1, lifecycleRevision: 1, serverNow: nowIso }
    else if (parts[2] === 'lobby') data = exam
    else if (parts[2] === 'availability') data = { examId: 'e1', examTitle: exam.title, status: 'OPEN', startAt: null, endAt: null, timezone: 'UTC', durationMinutes: 30, deadlineRule: 'FULL_DURATION', canJoin: false, canStart: false, canResume: false, reasonCode: 'ATTEMPT_LIMIT', attemptsRemaining: 0, effectiveExpiresAt: null, followed: false, participant: true, calendarState: 'CLOSED', latestSessionId: 's2', latestSessionStatus: 'SUBMITTED', canView: true, scheduleRevision: 1, serverNow: nowIso }
    else if (parts[2] === 'participation') data = identity === 'student' && url.searchParams.get('revoked') !== null ? {} : { examId: 'e1', status: 'ACTIVE', source: 'ASSIGNED', joinedAt: '2026-10-01T00:00:00Z', attemptLimit: 1, extraAttempts: 1, effectiveAttemptLimit: 2, used: 2, remaining: 0, activeAttempt: false, followed: false,
      attempts: [{ sessionId: 's1', attemptNumber: 1, status: 'SUBMITTED', startedAt: '2026-10-05T01:00:00', invalidated: true, invalidationReason: 'Sai phòng thi' }, { sessionId: 's2', attemptNumber: 2, status: 'SUBMITTED', startedAt: '2026-10-06T01:00:00', invalidated: false }],
      grants: [{ grantId: 'g1', quantity: 1, reason: 'Sự cố mạng', createdAt: '2026-10-05T10:00:00Z' }] }
    else if (parts[2] === 'participants' && parts.length === 3 && req.method === 'GET') data = { content: [row('aaaa1111', 'Học sinh A', 'ACTIVE'), row('bbbb2222', null, 'ACTIVE', { extraAttempts: 1, effectiveAttemptLimit: 2 }), row('cccc3333', 'Học sinh C', 'REVOKED', { revocationReason: 'Nghỉ học' })], page: 0, size: 50, totalElements: 3, totalPages: 1, counts }
    else if (parts[2] === 'participants' && parts.length === 3 && req.method === 'POST') {
      const inputs = body.userIds ?? body.emails ?? []
      data = { results: inputs.map((input, i) => ({ input, ...(i === 0 ? { userId: 'aaaa1111-1111-2222-3333-444444444444', displayName: 'Học sinh A' } : {}), outcome: i === 0 ? 'ASSIGNED' : i === 1 ? 'NOT_FOUND' : 'NOT_A_STUDENT' })), counts }
    }
    else if (parts[4] === 'attempt-grants' && req.method === 'POST') data = { userId: parts[3], extraAttempts: 1, effectiveAttemptLimit: 2, grant: { grantId: 'g2', quantity: body.quantity, createdAt: nowIso }, replayed: false }
    else if (parts[4] === 'attempt-grants') data = [{ id: 'g1', quantity: 1, reason: 'Sự cố mạng', createdAt: '2026-10-05T10:00:00Z' }]
    else if (parts[4] === 'revoke') data = row(parts[3].slice(0, 8), 'Học sinh A', 'REVOKED')
    else if (parts[3] === 'attempts') data = [att(1, { invalidated: true, invalidationReason: 'Sai phòng thi', invalidatedAt: nowIso }), att(2)]
    else if (parts[2] === 'invalidate') { if (body.reason === 'conflict') { res.statusCode = 409; res.end(JSON.stringify({ status: 409, detail: 'Phiên đã thay đổi' })); return } data = att(2, { invalidated: true, invalidationReason: body.reason }) }
    else if (parts[3] === 'monitor' || parts[2] === 'monitor') data = []
    else data = []
  }
  // Grading fixtures (browser checks of the unsaved-draft guard only; the real contract is manual-grading-api.md)
  else if (url.pathname === '/exams' && req.method === 'GET') data = { content: [{ id: 'e1', title: 'Fixture exam', status: 'CLOSED', questionIds: [] }, { id: 'e2', title: 'Other exam', status: 'CLOSED', questionIds: [] }], totalElements: 2, totalPages: 1, number: 0 }
  else if (url.pathname === '/submissions/overview') data = { totalSubmissions: 2, needsReview: 1, pending: 0, graded: 1, released: 1, pendingManualItems: 1 }
  else if (url.pathname === '/submissions' && req.method === 'GET') {
    const row = (id, status) => ({ id, examId: 'e1', examTitle: 'Fixture exam', studentId: `${id}-0000-aaaa`, attemptNumber: 1, status, score: null, totalScore: 10, publishedScore: null, pendingManualItemCount: status === 'NEEDS_REVIEW' ? 1 : 0, submittedAt: null, gradedAt: null, durationSeconds: null, feedback: null, resultRevision: 1, publishedRevision: null })
    data = { content: [row('aaaa1111', 'NEEDS_REVIEW'), row('bbbb2222', 'NEEDS_REVIEW')], totalElements: 2, totalPages: 1, number: 0 }
  } else if (/^\/submissions\/[^/]+$/.test(url.pathname)) {
    const id = url.pathname.split('/').pop()
    data = { id, examId: 'e1', examTitle: 'Fixture exam', studentId: `${id}-0000-aaaa`, attemptNumber: 1, status: 'NEEDS_REVIEW', score: null, totalScore: 10, publishedScore: null, pendingManualItemCount: 1, submittedAt: null, gradedAt: null, durationSeconds: null, feedback: null, resultRevision: 1, publishedRevision: null,
      items: [{ questionId: 'q1', questionVersion: 1, position: 1, questionContent: 'Viết đoạn văn', questionType: 'ESSAY', pointValue: 10, answerContent: 'Bài làm', selectedOptionContent: null, autoScore: null, manualScore: null, awardedPoints: null, needsReview: true, feedback: null, manualGradedBy: null, manualGradedAt: null }] }
  } else if (/\/result-policy$/.test(url.pathname)) data = { autoRelease: true, showScore: true, showQuestionBreakdown: true, showFeedback: true, showCorrectAnswers: false, showExplanation: false }
  else data = { content: [], totalElements: 0, totalPages: 0, number: 0 }
  res.end(JSON.stringify(data))
}).listen(3110, '127.0.0.1', () => console.log('Fixture API: http://127.0.0.1:3110'))

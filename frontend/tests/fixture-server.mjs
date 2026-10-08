// Local, in-memory API fixture for shared-shell browser checks; never an app backend.
import { createServer } from 'node:http'
createServer(async (req, res) => {
  res.setHeader('Access-Control-Allow-Origin', 'http://127.0.0.1:3107')
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type, Authorization')
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS')
  res.setHeader('Content-Type', 'application/json')
  if (req.method === 'OPTIONS') { res.end(); return }
  let raw = ''
  for await (const chunk of req) raw += chunk
  const body = raw ? JSON.parse(raw) : {}
  const url = new URL(req.url, 'http://localhost')
  const identity = body.email?.split('@')[0] || req.headers.authorization?.replace('Bearer fixture-', '') || 'student'
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

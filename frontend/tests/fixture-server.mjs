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
  } else if (['/subjects', '/categories', '/exams/my', '/exams/public', '/exams/invitations'].includes(url.pathname)) data = []
  else if (url.pathname === '/auth/logout') data = {}
  else data = { content: [], totalElements: 0, totalPages: 0, number: 0 }
  res.end(JSON.stringify(data))
}).listen(3110, '127.0.0.1', () => console.log('Fixture API: http://127.0.0.1:3110'))

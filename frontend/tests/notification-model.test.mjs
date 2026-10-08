import { test } from 'node:test'
import assert from 'node:assert/strict'
import { registerHooks } from 'node:module'
import { readFileSync, existsSync } from 'node:fs'
import ts from 'typescript'
import axios from 'axios'

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
const requests = []
axios.defaults.adapter = async config => { requests.push(config); return { status: 204, statusText: 'No Content', headers: {}, config, data: '' } }
const m = await import('../src/features/notifications/notificationModel.ts')
const push = await import('../src/features/notifications/pushClient.ts')

const n = over => ({ entityType: 'EXAM', entityId: 'e1', metadata: null, ...over })

test('notification target prefers the backend SPA link but only same-origin paths', () => {
  assert.equal(m.notificationTarget(n({ metadata: { link: '/exam/lobby/e1' } }), true), '/exam/lobby/e1')
  assert.equal(m.notificationTarget(n({ metadata: { link: '/exams/e1' } }), false), '/exams/e1')
  for (const bad of ['//evil.example/x', 'https://evil.example/x', '\\\\evil', 'javascript:alert(1)'])
    assert.equal(m.notificationTarget(n({ metadata: { link: bad } }), true), '/exam/lobby/e1')
  assert.equal(m.notificationTarget(n({}), false), '/exams/e1')
  assert.equal(m.notificationTarget(n({ entityType: 'EXAM_SESSION', entityId: 's1' }), true), '/exam/result/s1')
  assert.equal(m.notificationTarget(n({ entityType: 'QUESTION' }), false), '/questions')
  assert.equal(m.notificationTarget(n({ entityType: 'SOMETHING' }), false), '/dashboard')
})

test('icons: Scheduling types map, unknown types render generically', () => {
  assert.equal(m.notificationIcon('EXAM_RESCHEDULED'), 'calendar-clock')
  assert.equal(m.notificationIcon('EXAM_REMINDER'), 'bell')
  assert.equal(m.notificationIcon('FUTURE_TYPE'), 'bell')
})

test('relative time and unread badge', () => {
  const now = Date.parse('2030-01-10T12:00:00Z')
  assert.equal(m.relativeTime('2030-01-10T11:59:50Z', now), 'vừa xong')
  assert.match(m.relativeTime('2030-01-10T11:55:00Z', now), /5 phút/)
  assert.match(m.relativeTime('2030-01-10T09:00:00Z', now), /3 giờ/)
  assert.match(m.relativeTime('2030-01-08T12:00:00Z', now), /2 ngày/)
  assert.match(m.relativeTime('2029-12-01T12:00:00Z', now), /\d{2}\/\d{2}\/2029/)
  assert.equal(m.unreadLabel(7), '7'); assert.equal(m.unreadLabel(250), '99+')
})

test('VAPID key conversion (URL-safe base64, padding-free)', () => {
  const bytes = push.urlBase64ToUint8Array('BPh-_A')
  assert.deepEqual([...bytes], [0x04, 0xf8, 0x7e, 0xfc])
  assert.equal(push.urlBase64ToUint8Array('').length, 0)
})

test('push records are per account; logout clears the record and revokes with the captured Bearer only', async () => {
  push.setRecord('user-a', 'sub-1'); push.setRecord('user-b', 'sub-2')
  assert.equal(push.getRecord('user-a'), 'sub-1'); assert.equal(push.getRecord(undefined), undefined)
  requests.length = 0
  await push.pushLogoutCleanup('user-a', 'token-a')
  assert.equal(push.getRecord('user-a'), undefined)
  assert.equal(push.getRecord('user-b'), 'sub-2') // other accounts untouched
  assert.equal(requests.length, 1)
  assert.equal(requests[0].method, 'delete'); assert.match(requests[0].url, /\/notifications\/push\/subscriptions\/sub-1$/)
  assert.equal(requests[0].headers.get('Authorization'), 'Bearer token-a')
  for (const h of ['X-User-Id', 'X-User-Role', 'X-User-Persona', 'X-System-Privilege']) assert.equal(requests[0].headers.has(h), false)
  requests.length = 0
  await push.pushLogoutCleanup('user-c', 'token-c') // no subscription: no request
  assert.equal(requests.length, 0)
})

test('grading notification deep links: Teacher queue of one Exam and Student own result open in the SPA; foreign links are ignored', () => {
  const teacher = m.notificationTarget({ entityType: 'EXAM', entityId: 'e1', metadata: { link: '/grading?examId=e1' } }, false)
  assert.equal(teacher, '/grading?examId=e1')
  const student = m.notificationTarget({ entityType: 'EXAM_SESSION', entityId: 's1', metadata: { link: '/exam/result/s1' } }, true)
  assert.equal(student, '/exam/result/s1')
  assert.equal(m.notificationTarget({ entityType: 'EXAM_SESSION', entityId: 's1', metadata: { link: 'https://evil.example/x' } }, true), '/exam/result/s1')
  assert.equal(m.notificationTarget({ entityType: 'EXAM_SESSION', entityId: 's1', metadata: { link: '//evil.example' } }, true), '/exam/result/s1')
})

test('organization notification targets: invitations open the invitation block, org events open that organization; foreign links ignored', () => {
  const t = (n) => m.notificationTarget(n, false)
  assert.equal(t({ entityType: 'ORGANIZATION', entityId: 'o1', metadata: { link: '/organizations/o1' } }), '/organizations/o1')
  assert.equal(t({ entityType: 'ORGANIZATION', entityId: 'o1', metadata: {} }), '/organizations/o1')
  assert.equal(t({ entityType: 'ORGANIZATION_INVITATION', entityId: 'i1', metadata: { link: '/organizations/invitations' } }), '/organizations/invitations')
  assert.equal(t({ entityType: 'ORGANIZATION_INVITATION', entityId: 'i1', metadata: {} }), '/organizations?tab=invitations')
  assert.equal(t({ entityType: 'ORGANIZATION', entityId: 'o1', metadata: { link: 'https://evil.example/' } }), '/organizations/o1')
})

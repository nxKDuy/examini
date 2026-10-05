import { test, beforeEach } from 'node:test'
import assert from 'node:assert/strict'
import { registerHooks } from 'node:module'
import { readFileSync, existsSync } from 'node:fs'
import ts from 'typescript'
import axios from 'axios'

// Use the installed compiler to execute the actual Vite TS modules, without a new test dependency.
registerHooks({
  resolve(specifier, context, next) {
    if (specifier.startsWith('.') && context.parentURL?.includes('/src/')) {
      const url = new URL(specifier, context.parentURL)
      for (const ext of ['.ts', '.tsx']) {
        if (existsSync(new URL(url.href + ext))) return { url: url.href + ext, shortCircuit: true }
      }
    }
    return next(specifier, context)
  },
  load(url, context, next) {
    if (/\.tsx?$/.test(url)) {
      const source = readFileSync(new URL(url), 'utf8').replace('import.meta.env.VITE_API_BASE_URL', "'/'")
      return { format: 'module', shortCircuit: true, source: ts.transpileModule(source, {
        compilerOptions: { module: ts.ModuleKind.ESNext, jsx: ts.JsxEmit.ReactJSX }
      }).outputText }
    }
    return next(url, context)
  }
})
const storage = new Map()
globalThis.localStorage = { getItem: key => storage.get(key) ?? null, setItem: (key, value) => storage.set(key, value), removeItem: key => storage.delete(key) }
globalThis.window = { localStorage: globalThis.localStorage }
// Old persisted identity must be ignored, even when it claimed ADMIN.
localStorage.setItem('exam-auth', JSON.stringify({ state: { accessToken: 'old', refreshToken: 'refresh', user: { role: 'ADMIN' } }, version: 0 }))
let respond
let requests
axios.defaults.adapter = async config => {
  requests.push(config)
  const [status, data] = await respond(config)
  const response = { status, data, config, statusText: String(status), headers: {} }
  if (status >= 400) throw new axios.AxiosError('fixture error', 'ERR_BAD_REQUEST', config, null, response)
  return response
}
const { useAuthStore: store, hasPersona, isSystemAdmin, isAuthenticated } = await import('../src/store/authStore.ts')
assert.equal(store.getState().user, null)
assert.equal(store.getState().authReady, false)
assert.equal(store.getState().accessToken, 'old')
const { authApi } = await import('../src/features/auth/authApi.ts')
const { default: api } = await import('../src/lib/api.ts')
const profile = (persona, systemPrivilege = 'NONE') => ({ id: persona, email: 'fixture@example.test', fullName: 'Fixture', role: systemPrivilege === 'ADMIN' ? 'ADMIN' : persona, persona, systemPrivilege })
const tokens = { accessToken: 'access', refreshToken: 'refresh' }
beforeEach(() => { store.getState().logout(); requests = []; respond = () => [200, {}] })

for (const [persona, privilege] of [['STUDENT', 'NONE'], ['TEACHER', 'NONE'], ['TEACHER', 'ADMIN']]) {
  test(`login hydrates ${persona}/${privilege} from /auth/me`, async () => {
    respond = c => [200, c.url === '/auth/me' ? profile(persona, privilege) : tokens]
    await authApi.login({ email: 'fixture@example.test', password: 'fixture' })
    assert.deepEqual(store.getState().user, profile(persona, privilege))
    assert.equal(isAuthenticated(store.getState()), true)
    assert.equal(isSystemAdmin(store.getState().user), privilege === 'ADMIN')
    assert.equal(hasPersona(store.getState().user, persona), true)
    assert.equal(requests.find(c => c.url === '/auth/me').headers.get('Authorization'), 'Bearer access')
    assert.equal(JSON.parse(localStorage.getItem('exam-auth')).state.user, undefined)
  })
}

test('canonical privilege NONE overrides legacy ADMIN; legacy-only response remains compatible', () => {
  assert.equal(isSystemAdmin({ ...profile('TEACHER'), role: 'ADMIN' }), false)
  assert.equal(isSystemAdmin({ role: 'ADMIN' }), true)
  assert.equal(hasPersona({ role: 'TEACHER' }, 'TEACHER'), true)
})

for (const persona of ['STUDENT', 'TEACHER']) {
  test(`personal ${persona} registration uses supported contract`, async () => {
    respond = c => [200, c.url === '/auth/me' ? profile(persona) : tokens]
    await authApi.register({ email: 'fixture@example.test', password: 'fixture', fullName: 'Fixture', accountType: 'PERSONAL', persona, systemPrivilege: 'ADMIN' })
    const body = JSON.parse(requests[0].data)
    assert.equal(body.persona, persona)
    assert.equal(body.accountType, 'PERSONAL')
    assert.equal(body.systemPrivilege, undefined)
  })
}

test('unsupported Business and Admin registration do not send requests', async () => {
  await assert.rejects(authApi.register({ accountType: 'BUSINESS', persona: 'TEACHER' }))
  await assert.rejects(authApi.register({ accountType: 'PERSONAL', persona: 'ADMIN' }))
  assert.equal(requests.length, 0)
})

test('logout clears identity and tokens and revokes with the captured Bearer', async () => {
  store.getState().setTokens('access', 'refresh'); store.getState().setUser(profile('TEACHER'))
  await authApi.logout()
  assert.equal(store.getState().user, null)
  assert.equal(store.getState().accessToken, null)
  assert.equal(store.getState().refreshToken, null)
  assert.equal(requests[0].headers.get('Authorization'), 'Bearer access')
})

test('client removes every untrusted identity header', async () => {
  store.getState().setTokens('access', 'refresh')
  await api.get('/questions', { headers: { 'x-user-id': 'fake', 'X-User-Role': 'ADMIN', 'X-User-Persona': 'TEACHER', 'X-System-Privilege': 'ADMIN' } })
  assert.equal(requests[0].headers.get('Authorization'), 'Bearer access')
  for (const key of ['x-user-id', 'X-User-Role', 'X-User-Persona', 'X-System-Privilege']) assert.equal(requests[0].headers.has(key), false)
})

test('parallel 401s share one refresh and retry with the new Bearer', async () => {
  store.getState().setTokens('expired', 'refresh')
  respond = async c => c.url === '/auth/refresh' ? [200, { accessToken: 'new', refreshToken: 'new-refresh' }] : c.url === '/auth/me' ? [200, profile('TEACHER')] : c.headers.get('Authorization') === 'Bearer new' ? [200, {}] : [401, {}]
  await Promise.all([api.get('/exams'), api.get('/questions')])
  assert.equal(requests.filter(c => c.url === '/auth/refresh').length, 1)
  assert.equal(store.getState().accessToken, 'new')
})

test('retry returning 401 logs out instead of looping', async () => {
  store.getState().setTokens('expired', 'refresh'); store.getState().setUser(profile('TEACHER'))
  respond = c => c.url === '/auth/refresh' ? [200, { accessToken: 'new', refreshToken: 'new-refresh' }] : c.url === '/auth/me' ? [200, profile('TEACHER')] : [401, {}]
  await assert.rejects(api.get('/exams'))
  assert.equal(store.getState().user, null)
  assert.equal(store.getState().accessToken, null)
})

test('failed refresh clears identity; failed public login does not refresh', async () => {
  store.getState().setTokens('expired', 'refresh'); store.getState().setUser(profile('TEACHER'))
  respond = () => [401, {}]
  await assert.rejects(api.get('/exams'))
  assert.equal(store.getState().user, null)
  requests = []
  await assert.rejects(authApi.login({ email: 'fixture@example.test', password: 'bad' }))
  assert.equal(requests.length, 1)
})

test('hydration after logout cannot restore stale identity', async () => {
  store.getState().setTokens('access', 'refresh')
  let finish
  respond = () => new Promise(resolve => { finish = resolve })
  const restoring = authApi.restoreSession()
  await new Promise(resolve => setImmediate(resolve))
  store.getState().logout()
  finish([200, profile('TEACHER')])
  await restoring
  assert.equal(store.getState().user, null)
  assert.equal(store.getState().accessToken, null)
})

test('refresh after logout cannot resurrect tokens', async () => {
  store.getState().setTokens('expired', 'refresh')
  let finish
  respond = c => c.url === '/auth/refresh' ? new Promise(resolve => { finish = resolve }) : [401, {}]
  const pending = api.get('/exams')
  await new Promise(resolve => setImmediate(resolve))
  store.getState().logout()
  finish([200, tokens])
  await assert.rejects(pending)
  assert.equal(store.getState().accessToken, null)
})

test('malformed /auth/me fails closed', async () => {
  respond = c => [200, c.url === '/auth/me' ? { ...profile('TEACHER'), systemPrivilege: 'SUPER_ADMIN' } : tokens]
  await assert.rejects(authApi.login({ email: 'fixture@example.test', password: 'fixture' }))
  assert.equal(store.getState().user, null)
  assert.equal(store.getState().accessToken, null)
})

test('refresh rehydrates privilege changes from /auth/me', async () => {
  store.getState().setTokens('expired', 'refresh'); store.getState().setUser(profile('TEACHER', 'ADMIN'))
  respond = c => c.url === '/auth/refresh' ? [200, { accessToken: 'new', refreshToken: 'new-refresh' }] : c.url === '/auth/me' ? [200, profile('TEACHER')] : c.headers.get('Authorization') === 'Bearer new' ? [200, {}] : [401, {}]
  await api.get('/exams')
  assert.equal(isSystemAdmin(store.getState().user), false)
  assert.equal(store.getState().user.systemPrivilege, 'NONE')
})

test('late 401 from an old session does not log out the new account', async () => {
  store.getState().setTokens('old-access', 'old-refresh')
  let finish
  respond = () => new Promise(resolve => { finish = resolve })
  const pending = api.get('/exams')
  await new Promise(resolve => setImmediate(resolve))
  store.getState().logout()
  store.getState().setTokens('new-account-access', 'new-account-refresh')
  store.getState().setUser(profile('STUDENT'))
  finish([401, {}])
  await assert.rejects(pending)
  assert.equal(store.getState().accessToken, 'new-account-access')
  assert.equal(store.getState().user.persona, 'STUDENT')
})

test('late 401 after a same-session refresh retries with the current access token', async () => {
  store.getState().setTokens('expired', 'refresh')
  let finish
  respond = c => {
    if (c.url === '/auth/refresh') return [200, { accessToken: 'new', refreshToken: 'new-refresh' }]
    if (c.url === '/auth/me') return [200, profile('TEACHER')]
    if (c.headers.get('Authorization') === 'Bearer new') return [200, {}]
    if (c.url === '/questions') return new Promise(resolve => { finish = resolve })
    return [401, {}]
  }
  const pending = api.get('/questions')
  await new Promise(resolve => setImmediate(resolve))
  await api.get('/exams')
  finish([401, {}])
  await pending
  assert.equal(store.getState().accessToken, 'new')
  assert.equal(requests.filter(c => c.url === '/auth/refresh').length, 1)
})

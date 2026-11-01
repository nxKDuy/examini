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
    if (/\.tsx?$/.test(url)) {
      const source = readFileSync(new URL(url), 'utf8').replace('import.meta.env.VITE_API_BASE_URL', "'/'")
      return { format: 'module', shortCircuit: true, source: ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.ESNext, jsx: ts.JsxEmit.ReactJSX } }).outputText }
    }
    return next(url, context)
  },
})

const storage = new Map()
globalThis.localStorage = { getItem: k => storage.get(k) ?? null, setItem: (k, v) => storage.set(k, v), removeItem: k => storage.delete(k) }
const listeners = new Set()
globalThis.window = { localStorage: globalThis.localStorage, addEventListener: (n, f) => listeners.add(f), removeEventListener: (n, f) => listeners.delete(f) }
const c = await import('../src/features/auth/crossTab.ts')
const { useAuthStore } = await import('../src/store/authStore.ts')
const jwt = sub => `h.${Buffer.from(JSON.stringify({ sub })).toString('base64url')}.s`
const fire = (key, value) => listeners.forEach(f => f({ key, newValue: value === null ? null : JSON.stringify({ state: value, version: 0 }) }))

test('subject is read from the token payload', () => {
  assert.equal(c.tokenSubject(jwt('u-1')), 'u-1')
  assert.equal(c.tokenSubject('garbage'), null)
  assert.equal(c.tokenSubject(null), null)
})

test('decide: same account adopts, other account switches, sign-out follows', () => {
  const cur = { accessToken: jwt('u1'), refreshToken: 'r1', userId: 'u1' }
  assert.equal(c.decide(cur, { accessToken: cur.accessToken, refreshToken: 'r1' }), 'ignore')
  assert.equal(c.decide(cur, { accessToken: jwt('u1'), refreshToken: 'r2' }), 'adopt')
  assert.equal(c.decide(cur, { accessToken: jwt('u2'), refreshToken: 'r9' }), 'switch')
  assert.equal(c.decide(cur, { accessToken: null, refreshToken: null }), 'logout')
  assert.equal(c.decide({ accessToken: null, refreshToken: null, userId: null }, { accessToken: jwt('u1'), refreshToken: 'r' }), 'restore')
  assert.equal(c.decide({ accessToken: null, refreshToken: null, userId: null }, { accessToken: null, refreshToken: null }), 'ignore')
})

test('watcher applies other tabs\' changes to this tab', () => {
  const calls = []
  const stop = c.watchOtherTabs({ restore: other => calls.push(other ? 'restore-other' : 'restore'), switched: () => calls.push('switched') })
  const s = useAuthStore.getState()
  s.setTokens(jwt('u1'), 'r1'); s.setUser({ id: 'u1', email: 'a@x', fullName: 'A', role: 'STUDENT' })
  fire('exam-auth', { accessToken: jwt('u1'), refreshToken: 'r2' })
  assert.equal(useAuthStore.getState().refreshToken, 'r2')
  assert.equal(useAuthStore.getState().user?.id, 'u1')
  fire('other-key', null)
  fire('exam-auth', { accessToken: jwt('u2'), refreshToken: 'r3' })
  assert.deepEqual(calls, ['switched'])
  assert.equal(useAuthStore.getState().refreshToken, 'r2') // unchanged until the page reloads
  fire('exam-auth', null)
  assert.equal(useAuthStore.getState().accessToken, null)
  fire('exam-auth', { accessToken: jwt('u1'), refreshToken: 'r4' }) // same account signed in again
  assert.deepEqual(calls, ['switched', 'restore'])
  assert.equal(useAuthStore.getState().refreshToken, 'r4')
  useAuthStore.getState().setUser({ id: 'u1', email: 'a@x', fullName: 'A', role: 'STUDENT' })
  fire('exam-auth', null)
  fire('exam-auth', { accessToken: jwt('u3'), refreshToken: 'r5' }) // sign-out + sign-in as someone else
  assert.deepEqual(calls, ['switched', 'restore', 'restore-other'])
  stop()
  assert.equal(listeners.size, 0)
})

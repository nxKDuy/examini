import { test, beforeEach } from 'node:test'
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
// A fake push-capable browser so the real cleanup path (subscription lookup + unsubscribe) runs.
const events = []
let current = null
const makeSub = name => ({ name, unsubscribed: false, async unsubscribe() { events.push(`unsubscribe:${name}`); this.unsubscribed = true; if (current === this) current = null; return true },
  toJSON() { return { endpoint: `https://push.example/${name}`, keys: { p256dh: 'p', auth: 'a' } } } })
const deferred = () => { let resolve; const promise = new Promise(r => { resolve = r }); return { promise, resolve } }
// Controllable fake browser steps for the enable flow
const gates = { permission: null, subscribe: null, post: null, lookup: null, del: null }
let permissionResult = 'granted', subscribeCalls = 0
const registration = { pushManager: { getSubscription: async () => { const found = current; if (gates.lookup) await gates.lookup.promise; return found }, subscribe: async () => { subscribeCalls++; if (gates.subscribe) await gates.subscribe.promise; current = current ?? makeSub('NEW'); return current } } } // like the real PushManager: one subscription per registration
globalThis.Notification = { requestPermission: async () => { if (gates.permission) await gates.permission.promise; return permissionResult } }
globalThis.window = { localStorage: globalThis.localStorage, isSecureContext: true, PushManager: {}, Notification: {} }
Object.defineProperty(globalThis, 'navigator', { configurable: true, value: { serviceWorker: { getRegistration: async () => registration, register: async () => registration, ready: Promise.resolve(registration) } } })
const requests = []
axios.defaults.adapter = async config => {
  events.push(`${config.method}:${config.url}`)
  requests.push(config)
  if (config.method === 'post' && gates.post) await gates.post.promise
  if (config.method === 'delete' && gates.del) { const g = gates.del; gates.del = null; await g.promise } // only the first DELETE waits
  await new Promise(r => setTimeout(r, 15)) // the server call is slower than the local unsubscribe
  if (config.method === 'post') return { status: 201, statusText: 'Created', headers: {}, config, data: { id: 'server-sub-9', createdAt: '2030-01-01T00:00:00Z' } }
  return { status: 204, statusText: 'No Content', headers: {}, config, data: '' }
}
const { useAuthStore: store } = await import('../src/store/authStore.ts')
const push = await import('../src/features/notifications/pushClient.ts')
const { watchPushCleanup } = await import('../src/features/notifications/pushAuthWatcher.ts')

const user = id => ({ id, email: `${id}@example.test`, fullName: id, role: 'STUDENT', persona: 'STUDENT', systemPrivilege: 'NONE' })
const signIn = (id, token) => store.setState({ accessToken: token, refreshToken: 'r-' + token, user: user(id), authReady: true })
let stop = () => {}
beforeEach(() => { stop(); storage.clear(); events.length = 0; requests.length = 0; current = null; gates.permission = gates.subscribe = gates.post = gates.lookup = gates.del = null; permissionResult = 'granted'; subscribeCalls = 0; store.setState({ accessToken: null, refreshToken: null, user: null, authReady: true }) })

test('forced logout (failed refresh) cleans up with the OLD identity and token; browser subscription goes first', async () => {
  signIn('user-a', 'tok-a'); push.setRecord('user-a', 'sub-1'); current = makeSub('A')
  stop = watchPushCleanup()
  store.getState().logout() // what lib/api.ts does when refresh fails: no Logout button involved
  await push.pushCleanupSettled()
  assert.deepEqual(events, ['unsubscribe:A', 'delete:/notifications/push/subscriptions/sub-1'])
  assert.equal(requests[0].headers.get('Authorization'), 'Bearer tok-a')
  for (const h of ['X-User-Id', 'X-User-Role', 'X-User-Persona', 'X-System-Privilege']) assert.equal(requests[0].headers.has(h), false)
  assert.equal(push.getRecord('user-a'), undefined)
  assert.equal(current, null)
})

test('account switch: only the previous account is cleaned; the next account subscribes afterwards and keeps its subscription', async () => {
  signIn('user-a', 'tok-a'); push.setRecord('user-a', 'sub-1'); current = makeSub('A')
  stop = watchPushCleanup()
  store.getState().logout()
  signIn('user-b', 'tok-b') // login right away, before the old cleanup has finished
  await push.pushCleanupSettled() // what enable()/reconcile wait for before subscribing
  const subB = makeSub('B'); current = subB; push.setRecord('user-b', 'sub-2') // user B enables push after the cleanup
  await push.pushCleanupSettled()
  assert.equal(subB.unsubscribed, false) // the late old cleanup did not touch the new subscription
  assert.equal(push.getRecord('user-b'), 'sub-2'); assert.equal(push.getRecord('user-a'), undefined)
  assert.equal(requests.length, 1); assert.match(requests[0].url, /sub-1$/); assert.equal(requests[0].headers.get('Authorization'), 'Bearer tok-a')
})

test('token refresh for the same account is not a session end', async () => {
  signIn('user-a', 'tok-a'); push.setRecord('user-a', 'sub-1'); current = makeSub('A')
  stop = watchPushCleanup()
  store.getState().setTokens('tok-a2', 'r-a2'); store.getState().setUser(user('user-a'))
  await push.pushCleanupSettled()
  assert.equal(requests.length, 0); assert.equal(push.getRecord('user-a'), 'sub-1'); assert.equal(current.unsubscribed, false)
})

test('a session that was already dead at start-up leaves no subscription or records behind', async () => {
  push.setRecord('user-z', 'sub-z'); current = makeSub('Z')
  stop = watchPushCleanup() // store is signed out
  await push.pushCleanupSettled()
  assert.deepEqual(push.readRecords(), {}); assert.equal(current, null)
  assert.equal(requests.length, 0) // no token exists to call the server with; the browser endpoint is dead
})

test('signing in does not trigger cleanup of the account that just signed in', async () => {
  stop = watchPushCleanup()
  await push.pushCleanupSettled() // start-up cleanup (signed out) is finished before anyone can sign in and enable push
  signIn('user-c', 'tok-c'); push.setRecord('user-c', 'sub-3'); current = makeSub('C')
  await push.pushCleanupSettled()
  assert.equal(current.unsubscribed, false); assert.equal(push.getRecord('user-c'), 'sub-3'); assert.equal(requests.length, 0)
})

const { enablePush } = push
const current_ = userId => () => { const c = store.getState(); return c.sessionVersion === version && c.user?.id === userId }
let version = 0
const begin = (userId, token) => { signIn(userId, token); version = store.getState().sessionVersion; return { userId, token, publicKey: 'BPh-_A', isCurrent: current_(userId) } }

test('enable: the normal path subscribes, registers with the account token and records the id', async () => {
  const o = begin('user-a', 'tok-a')
  assert.equal(await enablePush(o), 'enabled')
  assert.equal(push.getRecord('user-a'), 'server-sub-9'); assert.equal(current.name, 'NEW'); assert.equal(current.unsubscribed, false)
  const post = requests.find(r => r.method === 'post'); assert.equal(post.headers.get('Authorization'), 'Bearer tok-a')
  assert.equal(JSON.parse(post.data).endpoint, 'https://push.example/NEW')
})

test('enable pending on the permission prompt, user switches account: nothing is subscribed or registered', async () => {
  const o = begin('user-a', 'tok-a'); stop = watchPushCleanup()
  gates.permission = deferred()
  const p = enablePush(o)
  store.getState().logout(); signIn('user-b', 'tok-b')
  gates.permission.resolve()
  assert.equal(await p, 'stale')
  assert.equal(subscribeCalls, 0); assert.equal(requests.some(r => r.method === 'post'), false)
  assert.equal(push.getRecord('user-b'), undefined)
})

test('enable pending on subscribe, user logs out: the late subscription is dropped, not registered', async () => {
  const o = begin('user-a', 'tok-a'); stop = watchPushCleanup()
  gates.subscribe = deferred()
  const p = enablePush(o)
  await new Promise(r => setTimeout(r, 5)) // flow is now waiting inside subscribe()
  store.getState().logout()
  await push.pushCleanupSettled()
  gates.subscribe.resolve()
  assert.equal(await p, 'stale')
  assert.equal(requests.some(r => r.method === 'post'), false) // never POSTed under any account
  assert.equal(push.getRecord('user-a'), undefined)
  assert.equal(current, null) // the exact late subscription was unsubscribed
  assert.ok(events.includes('unsubscribe:NEW'))
})

test('enable pending on the POST, user switches account: browser subscription is dropped and the server record is deleted with the OLD token', async () => {
  const o = begin('user-a', 'tok-a'); stop = watchPushCleanup()
  gates.post = deferred()
  const p = enablePush(o)
  await new Promise(r => setTimeout(r, 20)) // POST is in flight
  store.getState().logout(); signIn('user-b', 'tok-b')
  await push.pushCleanupSettled()
  gates.post.resolve()
  assert.equal(await p, 'stale')
  assert.equal(push.getRecord('user-b'), undefined); assert.equal(push.getRecord('user-a'), undefined)
  assert.equal(current, null); assert.ok(events.includes('unsubscribe:NEW'))
  const del = requests.filter(r => r.method === 'delete').at(-1)
  assert.match(del.url, /subscriptions\/server-sub-9$/); assert.equal(del.headers.get('Authorization'), 'Bearer tok-a')
})

test('after a stale enable the next account can enable cleanly and keeps its own subscription', async () => {
  const o = begin('user-a', 'tok-a'); stop = watchPushCleanup()
  gates.subscribe = deferred()
  const p = enablePush(o)
  await new Promise(r => setTimeout(r, 5))
  store.getState().logout(); gates.subscribe.resolve(); assert.equal(await p, 'stale')
  gates.subscribe = null; await push.pushCleanupSettled()
  const b = begin('user-b', 'tok-b')
  assert.equal(await enablePush(b), 'enabled')
  assert.equal(push.getRecord('user-b'), 'server-sub-9'); assert.equal(current.unsubscribed, false)
})

test('permission denied is reported, not registered', async () => {
  const o = begin('user-a', 'tok-a'); permissionResult = 'denied'
  assert.equal(await enablePush(o), 'denied'); assert.equal(subscribeCalls, 0)
})

test('A is pending on subscribe, logs out; B enables BEFORE A finishes: B keeps its own subscription and record', async () => {
  const a = begin('user-a', 'tok-a'); stop = watchPushCleanup()
  gates.subscribe = deferred()
  const pa = enablePush(a)
  await new Promise(r => setTimeout(r, 5)) // A waits inside subscribe()
  store.getState().logout() // cleanup runs now and finds no subscription yet
  await push.pushCleanupSettled()
  const b = begin('user-b', 'tok-b')
  const pb = enablePush(b) // B clicks Enable while A is still pending
  await new Promise(r => setTimeout(r, 5))
  gates.subscribe.resolve() // A's subscribe finally returns
  assert.equal(await pa, 'stale')
  gates.subscribe = null
  assert.equal(await pb, 'enabled')
  assert.equal(push.getRecord('user-b'), 'server-sub-9'); assert.equal(push.getRecord('user-a'), undefined)
  assert.ok(current && !current.unsubscribed) // B's endpoint was never unsubscribed by A's stale undo
  const posts = requests.filter(r => r.method === 'post'); assert.equal(posts.length, 1); assert.equal(posts[0].headers.get('Authorization'), 'Bearer tok-b')
})

test('a stale enable that finds an existing subscription never unsubscribes it', async () => {
  const a = begin('user-a', 'tok-a') // no cleanup watcher here: this isolates enable's own undo
  const owned = makeSub('B-OWNED'); current = owned // a subscription already exists when A looks it up
  gates.lookup = deferred()
  const pa = enablePush(a)
  await new Promise(r => setTimeout(r, 5)) // A is inside getSubscription()
  store.getState().logout()
  gates.lookup.resolve()
  assert.equal(await pa, 'stale')
  assert.equal(owned.unsubscribed, false) // A did not create it, so A's undo leaves it alone
})

test('reconcile for A, delayed in the subscription lookup, does not remove the subscription B made meanwhile', async () => {
  const a = begin('user-a', 'tok-a')
  current = makeSub('B-OWNED'); push.setRecord('user-b', 'sub-2') // B's endpoint and record exist by the time A's lookup returns
  gates.lookup = deferred()
  const pr = push.reconcilePush('user-a', a.isCurrent)
  await new Promise(r => setTimeout(r, 5)) // A's reconcile is inside getSubscription()
  const owned = current
  signIn('user-b', 'tok-b')
  gates.lookup.resolve()
  await pr
  assert.equal(owned.unsubscribed, false); assert.equal(push.getRecord('user-b'), 'sub-2')
})

test('reconcile for the same account waits for a pending enable (POST in flight, no record yet) and keeps its subscription', async () => {
  const a = begin('user-a', 'tok-a')
  gates.post = deferred()
  const pe = enablePush(a)
  await new Promise(r => setTimeout(r, 20)) // subscribed; POST in flight; no record yet
  const pr = push.reconcilePush('user-a', a.isCurrent)
  await new Promise(r => setTimeout(r, 5))
  assert.equal(current.unsubscribed, false)
  gates.post.resolve()
  assert.equal(await pe, 'enabled'); await pr
  assert.equal(current.unsubscribed, false); assert.equal(push.getRecord('user-a'), 'server-sub-9')
})

test('reconcile still removes a leftover subscription that the signed-in account did not create', async () => {
  const a = begin('user-a', 'tok-a'); const left = makeSub('LEFTOVER'); current = left
  await push.reconcilePush('user-a', a.isCurrent)
  assert.equal(left.unsubscribed, true)
})

test('disable pending on the DELETE, A logs out, B logs in and enables: disable A finishes, B keeps its record and endpoint', async () => {
  const a = begin('user-a', 'tok-a'); stop = watchPushCleanup()
  assert.equal(await enablePush(a), 'enabled'); assert.equal(push.getRecord('user-a'), 'server-sub-9')
  const hold = gates.del = deferred()
  const pd = push.disablePush({ userId: 'user-a', token: 'tok-a', isCurrent: a.isCurrent })
  await new Promise(r => setTimeout(r, 20)) // DELETE of A's record is in flight
  store.getState().logout(); await new Promise(r => setTimeout(r, 5)) // account-end cleanup finishes on its own
  const b = begin('user-b', 'tok-b')
  const pb = enablePush(b) // B enables while disable A is still pending
  await new Promise(r => setTimeout(r, 5))
  hold.resolve()
  await pd; assert.equal(await pb, 'enabled')
  assert.equal(push.getRecord('user-b'), 'server-sub-9'); assert.equal(push.getRecord('user-a'), undefined)
  assert.ok(current && !current.unsubscribed) // B's endpoint is untouched
  const deletes = requests.filter(r => r.method === 'delete')
  assert.ok(deletes.length >= 1 && deletes.every(r => r.headers.get('Authorization') === 'Bearer tok-a')) // no B authority on A's requests
})

test('normal disable removes this account\'s endpoint and record with its own token', async () => {
  const a = begin('user-a', 'tok-a')
  assert.equal(await enablePush(a), 'enabled'); const sub = current
  await push.disablePush({ userId: 'user-a', token: 'tok-a', isCurrent: a.isCurrent })
  assert.equal(sub.unsubscribed, true); assert.equal(push.getRecord('user-a'), undefined)
  const del = requests.find(r => r.method === 'delete'); assert.match(del.url, /subscriptions\/server-sub-9$/); assert.equal(del.headers.get('Authorization'), 'Bearer tok-a')
})

import { revokePushSubscription } from '../../lib/api'
import { notificationApi } from './notificationApi'

const KEY = 'examini-push' // { [userId]: subscriptionId } for this browser only

export const pushSupported = () =>
  typeof window !== 'undefined' && 'serviceWorker' in navigator && 'PushManager' in window && 'Notification' in window && window.isSecureContext

export function urlBase64ToUint8Array(value: string): Uint8Array<ArrayBuffer> {
  const padded = (value + '='.repeat((4 - (value.length % 4)) % 4)).replace(/-/g, '+').replace(/_/g, '/')
  const raw = atob(padded)
  const out = new Uint8Array(new ArrayBuffer(raw.length))
  for (let i = 0; i < raw.length; i++) out[i] = raw.charCodeAt(i)
  return out
}

export function readRecords(): Record<string, string> {
  try { return JSON.parse(localStorage.getItem(KEY) || '{}') as Record<string, string> } catch { return {} }
}
function writeRecords(r: Record<string, string>) { try { localStorage.setItem(KEY, JSON.stringify(r)) } catch { /* optional */ } }
export const getRecord = (userId: string | undefined) => (userId ? readRecords()[userId] : undefined)
export function setRecord(userId: string, id: string) { writeRecords({ ...readRecords(), [userId]: id }) }
export function clearRecord(userId: string) { const r = readRecords(); delete r[userId]; writeRecords(r) }

export async function currentSubscription(): Promise<PushSubscription | null> {
  if (!pushSupported()) return null
  try { return (await (await navigator.serviceWorker.getRegistration())?.pushManager.getSubscription()) ?? null } catch { return null }
}

async function dropBrowserSubscription() { try { await (await currentSubscription())?.unsubscribe() } catch { /* best effort */ } }

// Cleanups queue up; anything that creates or reconciles a subscription waits for them, so an old account's
// cleanup finishing late can never unsubscribe a subscription the next account just made.
let inFlight: Promise<void> = Promise.resolve()
export const pushCleanupSettled = () => inFlight

/**
 * The account lost its session (logout, expiry, failed refresh, account switch). With the OLD identity and token captured at that
 * moment: drop the browser subscription first (local and immediate), then delete the server subscription (best effort; an expired
 * token just fails and the dead endpoint is revoked by the provider answer on the next send).
 */
export function pushLogoutCleanup(userId: string | undefined, token: string | null): Promise<void> {
  const id = getRecord(userId)
  if (userId) clearRecord(userId)
  const run = (async () => {
    await dropBrowserSubscription()
    try { if (id && token) await revokePushSubscription(token, id) } catch { /* best effort */ }
  })()
  inFlight = inFlight.then(() => run)
  return run
}

/** Nobody is signed in: any push subscription left on this browser is stale, so remove it and forget every local record. */
export function pushSignedOutCleanup(): Promise<void> {
  const had = Object.keys(readRecords()).length > 0
  if (had) writeRecords({})
  const run = dropBrowserSubscription()
  inFlight = inFlight.then(() => run)
  return run
}

// Enables and reconciles share one queue (the browser has one push subscription per registration), so a reconcile never looks
// at the subscription while an enable for the same account is still waiting on its POST and has not recorded it yet.
let enableQueue: Promise<void> = Promise.resolve()
const inQueue = <T,>(work: () => Promise<T>): Promise<T> => {
  const turn = enableQueue.then(work)
  enableQueue = turn.then(() => undefined, () => undefined)
  return turn
}

/**
 * After a login: a browser subscription that this account did not create is not ours; remove it locally.
 * `isCurrent()` says whether the session that asked is still the active one; if not, this does nothing, so a slow reconcile for
 * account A can never remove the subscription account B has since made. Only the subscription object it read is unsubscribed.
 */
export function reconcilePush(userId: string, isCurrent: () => boolean = () => true): Promise<void> {
  return inQueue(async () => {
    await pushCleanupSettled()
    if (!isCurrent()) return
    const sub = await currentSubscription()
    if (!isCurrent()) return
    const record = getRecord(userId)
    if (sub && !record) { try { await sub.unsubscribe() } catch { /* best effort */ } }
    else if (!sub && record) clearRecord(userId)
  })
}

/**
 * Turn push off for ONE account. The record and the browser subscription are read once, up front, while this account is still the
 * active one; the best-effort server DELETE uses that account's own token (never whoever is signed in when it finishes). After the
 * await the session is checked again: if it changed, the account-end cleanup has already dropped A's subscription, so nothing
 * current is looked up or unsubscribed. Shares the enable/reconcile queue, so a later account's enable waits for this to finish.
 */
export function disablePush(o: { userId: string; token: string | null; isCurrent: () => boolean }): Promise<void> {
  return inQueue(async () => {
    const id = getRecord(o.userId)
    const sub = o.isCurrent() ? await currentSubscription() : null
    try { if (id && o.token) await revokePushSubscription(o.token, id) } catch { /* already gone: idempotent */ }
    if (o.isCurrent()) { try { await sub?.unsubscribe() } catch { /* best effort */ } }
    if (getRecord(o.userId) === id) clearRecord(o.userId)
  })
}

export type EnableResult = 'enabled' | 'denied' | 'stale'

/**
 * Opt in to browser push for ONE account. Every step awaits something the user can outlive (permission prompt, service worker,
 * subscribe, POST), so after each one we ask `isCurrent()` whether that same session is still the active one. If not, the
 * subscription this call created is unsubscribed (the exact object it holds, never "whatever the browser has now"), nothing is
 * recorded for the account that is signed in now, and a server record the POST may already have created is deleted with the OLD token.
 * Must be called from a click: it requests notification permission.
 */
export async function enablePush(o: { userId: string; token: string | null; publicKey: string; isCurrent: () => boolean }): Promise<EnableResult> {
  const permission = await Notification.requestPermission()
  if (!o.isCurrent()) return 'stale'
  if (permission !== 'granted') return 'denied'
  // The browser has ONE push subscription per registration, so enables never overlap: a later account waits until an earlier
  // (possibly stale) enable has finished and undone its own work, otherwise that undo could unsubscribe the later account's endpoint.
  return inQueue(() => subscribeAndRegister(o))
}

async function subscribeAndRegister(o: { userId: string; token: string | null; publicKey: string; isCurrent: () => boolean }): Promise<EnableResult> {
  await pushCleanupSettled() // an earlier account's cleanup must finish before this account subscribes
  if (!o.isCurrent()) return 'stale'
  const reg = await navigator.serviceWorker.register('/sw.js')
  await navigator.serviceWorker.ready
  if (!o.isCurrent()) return 'stale'
  const existing = await reg.pushManager.getSubscription()
  const sub = existing ?? await reg.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: urlBase64ToUint8Array(o.publicKey) })
  const undo = async (serverId?: string) => {
    // Only a subscription this call created is ours to remove; a pre-existing one belongs to whoever made it.
    if (!existing) { try { await sub.unsubscribe() } catch { /* best effort */ } }
    try { if (serverId && o.token) await revokePushSubscription(o.token, serverId) } catch { /* best effort */ }
  }
  if (!o.isCurrent()) { await undo(); return 'stale' } // subscribed after the session ended: do not even register it
  let created: { id: string }
  try { created = await notificationApi.subscribe(sub.toJSON()) } catch (e) { if (!o.isCurrent()) { await undo(); return 'stale' } throw e }
  if (!o.isCurrent()) { await undo(created.id); return 'stale' } // the POST may have been processed: clean both sides
  setRecord(o.userId, created.id)
  return 'enabled'
}

import { revokePushSubscription } from '../../lib/api'

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

/** Logout / account switch: delete this account's subscription server-side and drop it from the browser so the next account cannot receive it. */
export async function pushLogoutCleanup(userId: string | undefined, token: string | null) {
  const id = getRecord(userId)
  if (userId) clearRecord(userId)
  try { if (id && token) await revokePushSubscription(token, id) } catch { /* best effort */ }
  await dropBrowserSubscription()
}

/** After a login: a browser subscription that this account did not create is not ours; remove it locally. */
export async function reconcilePush(userId: string) {
  const sub = await currentSubscription()
  const record = getRecord(userId)
  if (sub && !record) await dropBrowserSubscription()
  else if (!sub && record) clearRecord(userId)
}

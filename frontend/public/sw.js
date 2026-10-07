// EXAMINI push service worker. Shows what the notification service sent and opens the SPA route.
// Only the path of the link is used and resolved against this origin: it never navigates elsewhere.
self.addEventListener('install', () => self.skipWaiting())
self.addEventListener('activate', event => event.waitUntil(self.clients.claim()))

function safePath(raw) {
  try {
    const u = new URL(String(raw || ''), self.location.origin)
    return (u.pathname.startsWith('/') && !u.pathname.startsWith('//') ? u.pathname : '/notifications') + u.search
  } catch { return '/notifications' }
}

self.addEventListener('push', event => {
  let data = {}
  try { data = event.data ? event.data.json() : {} } catch { data = { body: event.data ? event.data.text() : '' } }
  const title = typeof data.title === 'string' && data.title ? data.title : 'EXAMINI'
  event.waitUntil(self.registration.showNotification(title, {
    body: typeof data.body === 'string' ? data.body : '',
    tag: typeof data.tag === 'string' && data.tag ? data.tag : undefined,
    icon: '/favicon.svg',
    data: { path: safePath(data.url) },
  }))
})

self.addEventListener('notificationclick', event => {
  event.notification.close()
  const path = (event.notification.data && event.notification.data.path) || '/notifications'
  event.waitUntil((async () => {
    const all = await self.clients.matchAll({ type: 'window', includeUncontrolled: true })
    for (const client of all) {
      if (new URL(client.url).origin === self.location.origin) { await client.focus(); if ('navigate' in client) await client.navigate(path); return }
    }
    await self.clients.openWindow(path)
  })())
})

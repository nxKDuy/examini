import api, { apiBaseURL } from '../../lib/api'

/** Calendar sync: a personal iCalendar subscription (read-only secret link) and per-exam .ics files. */
export interface FeedStatus { active: boolean; createdAt: string | null; lastUsedAt: string | null }
export interface FeedLink { token: string; createdAt: string }

export const syncApi = {
  status: () => api.get<FeedStatus>('/exams/calendar/feed').then(r => r.data),
  create: () => api.post<FeedLink>('/exams/calendar/feed').then(r => r.data),
  revoke: () => api.delete('/exams/calendar/feed').then(() => undefined),
  /** Downloads one exam as a .ics file (authenticated request, then a local blob link). */
  async downloadExam(examId: string, title: string) {
    const res = await api.get<Blob>(`/exams/${examId}/calendar.ics`, { responseType: 'blob' })
    const url = URL.createObjectURL(res.data)
    const a = document.createElement('a')
    a.href = url; a.download = `${slug(title) || 'ky-thi'}.ics`
    document.body.appendChild(a); a.click(); a.remove()
    setTimeout(() => URL.revokeObjectURL(url), 1000)
  },
}

/** Absolute https/http URL of the feed, built from the API base the app itself uses (works behind /api or a gateway). */
export function feedUrl(token: string, base = apiBaseURL, origin = window.location.origin): string {
  const root = new URL(base.endsWith('/') ? base : base + '/', origin)
  return new URL(`exams/calendar/feed/${token}.ics`, root).toString()
}
/** Same link with the webcal scheme, which phones and desktop calendar apps open as "subscribe". */
export const webcalUrl = (url: string) => url.replace(/^https?:\/\//, 'webcal://')
/** Google Calendar "add by URL" page for a subscription. Google fetches the feed from its servers. */
export const googleSubscribeUrl = (url: string) => `https://calendar.google.com/calendar/r?cid=${encodeURIComponent(webcalUrl(url))}`
/** A local address cannot be fetched by Google's servers (phones on the same Wi-Fi may still reach a LAN IP). */
export const isLocalAddress = (url: string) => /^(https?|webcal):\/\/(localhost|127\.|\[::1\]|0\.0\.0\.0)/i.test(url)

/** Google Calendar quick-add link for one dated exam (UTC times, like the .ics file). */
export function googleEventUrl(e: { examTitle: string; startAt: string | null; endAt: string | null; durationMinutes: number }, link: string): string | null {
  if (!e.startAt) return null
  const start = new Date(e.startAt)
  const end = e.endAt ? new Date(e.endAt) : new Date(start.getTime() + Math.max(1, e.durationMinutes) * 60000)
  const fmt = (d: Date) => d.toISOString().replace(/[-:]/g, '').replace(/\.\d{3}/, '')
  const p = new URLSearchParams({ action: 'TEMPLATE', text: `Thi: ${e.examTitle}`, dates: `${fmt(start)}/${fmt(end)}`, details: `Vào phòng chờ: ${link}`, location: 'EXAMINI (trực tuyến)' })
  return `https://calendar.google.com/calendar/render?${p.toString()}`
}

export function slug(s: string): string {
  return s.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/g, 'd').replace(/Đ/g, 'D').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60)
}

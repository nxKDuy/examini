import axios, { type InternalAxiosRequestConfig } from 'axios'
import { useAuthStore, validateAuthUser } from '../store/authStore'

export const apiBaseURL = import.meta.env.VITE_API_BASE_URL || '/'
const api = axios.create({ baseURL: apiBaseURL, headers: { 'Content-Type': 'application/json' } })
const refreshClient = axios.create({ baseURL: apiBaseURL })
export const revokePushSubscription = (token: string, id: string) =>
  refreshClient.delete(`/notifications/push/subscriptions/${encodeURIComponent(id)}`, { headers: { Authorization: `Bearer ${token}` } })
export const revokeSession = (token: string) => refreshClient.post('/auth/logout', null, { headers: { Authorization: `Bearer ${token}` } })
let refreshFlight: { token: string; promise: Promise<string> } | null = null
const isPublicAuth = (url = '') => /\/auth\/(login|register|refresh|logout)(?:\?|$)/.test(url)

api.interceptors.request.use((config) => {
  const request = config as InternalAxiosRequestConfig & { _authSessionVersion?: number }
  const sessionVersion = useAuthStore.getState().sessionVersion
  if (request._authSessionVersion !== undefined && request._authSessionVersion !== sessionVersion) {
    throw new Error('Session changed')
  }
  request._authSessionVersion = sessionVersion
  for (const name of ['X-User-Id', 'X-User-Role', 'X-User-Persona', 'X-System-Privilege']) {
    config.headers.delete(name)
  }
  const token = useAuthStore.getState().accessToken
  config.headers.delete('Authorization')
  if (token && !isPublicAuth(config.url)) config.headers.set('Authorization', `Bearer ${token}`)
  return config
})

api.interceptors.response.use(
  (res) => res,
  async (error) => {
    const original = error.config
    if (error.response?.status !== 401 || !original || isPublicAuth(original.url)) throw error
    const state = useAuthStore.getState()
    const sentToken = original.headers.get('Authorization')
    // A response from a previous session must not invalidate the current session.
    if (original._authSessionVersion !== state.sessionVersion || !state.accessToken) throw error
    if (sentToken !== `Bearer ${state.accessToken}`) {
      if (original._retry) throw error
      original._retry = true
      return api(original)
    }
    if (original._retry || !state.refreshToken) {
      state.logout()
      throw error
    }
    original._retry = true
    const access = state.accessToken
    const refresh = state.refreshToken
    try {
      if (!refreshFlight || refreshFlight.token !== access) {
        const promise = refreshClient.post('/auth/refresh', { refreshToken: refresh }).then(async ({ data }) => {
          const current = useAuthStore.getState()
          if (current.accessToken !== access || current.refreshToken !== refresh || current.sessionVersion !== state.sessionVersion) throw new Error('Session changed')
          if (typeof data.accessToken !== 'string' || typeof data.refreshToken !== 'string') throw new Error('Invalid tokens')
          const me = await refreshClient.get('/auth/me', { headers: { Authorization: `Bearer ${data.accessToken}` } })
          const user = validateAuthUser(me.data)
          if (useAuthStore.getState().sessionVersion !== state.sessionVersion) throw new Error('Session changed')
          current.setTokens(data.accessToken, data.refreshToken)
          current.setUser(user)
          return data.accessToken as string
        })
        refreshFlight = { token: access, promise }
        void promise.finally(() => { if (refreshFlight?.promise === promise) refreshFlight = null }).catch(() => {})
      }
      await refreshFlight.promise
      return await api(original)
    } catch (refreshError) {
      if (useAuthStore.getState().accessToken === access) useAuthStore.getState().logout()
      throw refreshError
    }
  }
)

export default api

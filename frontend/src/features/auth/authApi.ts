import api, { revokeSession } from '../../lib/api'
import { useAuthStore, validateAuthUser, type AuthUser } from '../../store/authStore'
import { pushCleanupSettled } from '../notifications/pushClient'

interface LoginPayload { email: string; password: string }
interface RegisterPayload { email: string; password: string; fullName: string; accountType: 'PERSONAL'; persona: 'STUDENT' | 'TEACHER' }
interface GooglePayload { credential: string; persona?: 'STUDENT' | 'TEACHER' }
export interface AuthProviders { google: boolean; googleClientId: string | null }
let hydration: Promise<void> | null = null

async function hydrate() {
  const { accessToken, sessionVersion } = useAuthStore.getState()
  if (!accessToken) { useAuthStore.getState().logout(); return }
  const me = await api.get<AuthUser>('/auth/me')
  // Refresh may rotate tokens while hydrating. Logout/account switch must win.
  const current = useAuthStore.getState()
  if (current.accessToken && current.sessionVersion === sessionVersion && !current.user) {
    current.setUser(validateAuthUser(me.data))
  } else if (current.sessionVersion !== sessionVersion) {
    throw new Error('Session changed')
  }
}

async function authenticate(path: string, data: LoginPayload | RegisterPayload | GooglePayload) {
  useAuthStore.getState().logout()
  const sessionVersion = useAuthStore.getState().sessionVersion
  try {
    const res = await api.post(path, data)
    if (useAuthStore.getState().sessionVersion !== sessionVersion) throw new Error('Session changed')
    if (typeof res.data.accessToken !== 'string' || typeof res.data.refreshToken !== 'string') throw new Error('Invalid tokens')
    useAuthStore.getState().setTokens(res.data.accessToken, res.data.refreshToken)
    await hydrate()
    return res.data
  } catch (error) {
    if (useAuthStore.getState().sessionVersion === sessionVersion) useAuthStore.getState().logout()
    throw error
  }
}

export const authApi = {
  login: (data: LoginPayload) => authenticate('/auth/login', data),
  /** Google Identity Services credential; persona only when the server asks for it (409 PERSONA_REQUIRED). */
  google: (credential: string, persona?: 'STUDENT' | 'TEACHER') => authenticate('/auth/google', persona ? { credential, persona } : { credential }),
  providers: () => api.get<AuthProviders>('/auth/providers').then(r => r.data),
  register: (data: RegisterPayload) => {
    if (data.accountType !== 'PERSONAL' || !['STUDENT', 'TEACHER'].includes(data.persona)) {
      return Promise.reject(new Error('Only personal Student/Teacher registration is supported'))
    }
    return authenticate('/auth/register', { email: data.email, password: data.password, fullName: data.fullName, accountType: data.accountType, persona: data.persona })
  },
  restoreSession: () => {
    if (!hydration) {
      const sessionVersion = useAuthStore.getState().sessionVersion
      hydration = hydrate().catch(() => {
        if (useAuthStore.getState().sessionVersion === sessionVersion) useAuthStore.getState().logout()
      }).finally(() => { hydration = null })
    }
    return hydration
  },
  logout: async () => {
    // Clear local identity immediately; backend logout is best effort.
    const token = useAuthStore.getState().accessToken
    useAuthStore.getState().logout() // the auth runtime's watcher starts the push cleanup with this account's identity and token
    await pushCleanupSettled() // delete the push subscription while the token is still valid, then revoke the session
    try { if (token) await revokeSession(token) } catch { /* local session already cleared */ }
  },
}

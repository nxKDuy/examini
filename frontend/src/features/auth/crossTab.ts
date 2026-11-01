import { useAuthStore } from '../../store/authStore'

/** One account per browser: every tab follows the tokens saved by the last tab that signed in, refreshed or signed out. */
export const AUTH_STORAGE_KEY = 'exam-auth'
export const SWITCH_NOTICE_KEY = 'exam-auth-switched'

export interface StoredTokens { accessToken: string | null; refreshToken: string | null }

export function parseStored(raw: string | null): StoredTokens {
  try {
    const state = raw ? (JSON.parse(raw) as { state?: Partial<StoredTokens> }).state : undefined
    return {
      accessToken: typeof state?.accessToken === 'string' ? state.accessToken : null,
      refreshToken: typeof state?.refreshToken === 'string' ? state.refreshToken : null,
    }
  } catch { return { accessToken: null, refreshToken: null } }
}

/** Reads the tokens another tab may have just written (null when storage is unavailable). */
export function readStoredTokens(): StoredTokens | null {
  try { return parseStored(window.localStorage.getItem(AUTH_STORAGE_KEY)) } catch { return null }
}

/** JWT subject without verification: only used to tell "same account, new tokens" from "another account". */
export function tokenSubject(token: string | null): string | null {
  if (!token) return null
  try {
    const part = token.split('.')[1]
    if (!part) return null
    const json = atob(part.replace(/-/g, '+').replace(/_/g, '/').padEnd(Math.ceil(part.length / 4) * 4, '='))
    const sub = (JSON.parse(json) as { sub?: unknown }).sub
    return typeof sub === 'string' ? sub : null
  } catch { return null }
}

export type TabAction = 'ignore' | 'adopt' | 'logout' | 'restore' | 'switch'

/** What this tab does when another tab changes the saved session. */
export function decide(current: StoredTokens & { userId: string | null }, next: StoredTokens): TabAction {
  if (next.accessToken === current.accessToken && next.refreshToken === current.refreshToken) return 'ignore'
  if (!next.accessToken || !next.refreshToken) return current.accessToken ? 'logout' : 'ignore'
  if (!current.accessToken) return 'restore'
  const before = current.userId ?? tokenSubject(current.accessToken)
  const after = tokenSubject(next.accessToken)
  return before && after && before === after ? 'adopt' : 'switch'
}

export interface CrossTabHooks {
  /** Another tab signed in while this one was signed out; `otherAccount` when it is not the account this tab last had. */
  restore: (otherAccount: boolean) => void
  /** Another tab replaced the session with a different account without signing out first. */
  switched: () => void
}

/** Starts following other tabs; returns the unsubscribe function. */
export function watchOtherTabs(hooks: CrossTabHooks): () => void {
  // Signing in always clears the old session first, so an account switch arrives as "logout" then "restore".
  let lastUser: string | null = useAuthStore.getState().user?.id ?? null
  const onStorage = (e: StorageEvent) => {
    if (e.key !== null && e.key !== AUTH_STORAGE_KEY) return
    const state = useAuthStore.getState()
    const next = e.key === null ? { accessToken: null, refreshToken: null } : parseStored(e.newValue)
    switch (decide({ accessToken: state.accessToken, refreshToken: state.refreshToken, userId: state.user?.id ?? null }, next)) {
      case 'adopt': state.setTokens(next.accessToken!, next.refreshToken!); break // the other tab refreshed: reuse, do not refresh again
      case 'logout': lastUser = state.user?.id ?? tokenSubject(state.accessToken) ?? lastUser; state.logout(); break
      case 'restore': {
        const subject = tokenSubject(next.accessToken)
        state.setTokens(next.accessToken!, next.refreshToken!)
        hooks.restore(Boolean(lastUser && subject && lastUser !== subject))
        lastUser = subject ?? lastUser
        break
      }
      case 'switch': hooks.switched(); break
      default: break
    }
  }
  window.addEventListener('storage', onStorage)
  return () => window.removeEventListener('storage', onStorage)
}

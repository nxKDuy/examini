import { useAuthStore } from '../../store/authStore'
import { pushLogoutCleanup, pushSignedOutCleanup } from './pushClient'

/**
 * Installed by the shared auth runtime. Covers EVERY way a session ends, not just the Logout button: forced logout after a failed
 * refresh, expired session, account switch. It reads the identity/token that were valid just before the change.
 */
export function watchPushCleanup(): () => void {
  const signedOut = (s: ReturnType<typeof useAuthStore.getState>) => s.authReady && !s.accessToken && !s.user
  if (signedOut(useAuthStore.getState())) void pushSignedOutCleanup() // e.g. the stored session was already dead at start-up
  return useAuthStore.subscribe((state, prev) => {
    const before = prev.user?.id
    if (before && state.user?.id !== before) void pushLogoutCleanup(before, prev.accessToken)
    else if (signedOut(state) && !signedOut(prev)) void pushSignedOutCleanup()
  })
}

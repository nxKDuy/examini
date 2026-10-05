import { create } from 'zustand'
import { persist } from 'zustand/middleware'

export type Persona = 'STUDENT' | 'TEACHER'
export interface AuthUser {
  id: string
  email: string
  fullName: string
  role: 'ADMIN' | Persona
  // Optional only for compatibility with older /auth/me responses.
  persona?: Persona
  systemPrivilege?: 'NONE' | 'ADMIN'
}

export function validateAuthUser(user: AuthUser): AuthUser {
  if (!user || typeof user.id !== 'string' || typeof user.email !== 'string' || typeof user.fullName !== 'string' ||
    !['ADMIN', 'TEACHER', 'STUDENT'].includes(user.role) ||
    (user.persona !== undefined && !['STUDENT', 'TEACHER'].includes(user.persona)) ||
    (user.systemPrivilege !== undefined && !['NONE', 'ADMIN'].includes(user.systemPrivilege))) {
    throw new Error('Invalid /auth/me identity')
  }
  return user
}

export const isSystemAdmin = (user: AuthUser | null) =>
  user?.systemPrivilege === 'ADMIN' || (user?.systemPrivilege === undefined && user?.role === 'ADMIN')
export const hasPersona = (user: AuthUser | null, persona: Persona) =>
  (user?.persona ?? (user?.role === 'ADMIN' ? undefined : user?.role)) === persona
export const isAuthenticated = (state: AuthState) => Boolean(state.authReady && state.accessToken && state.user)

interface AuthState {
  accessToken: string | null
  refreshToken: string | null
  user: AuthUser | null
  sessionVersion: number
  authReady: boolean
  setTokens: (access: string, refresh: string) => void
  setUser: (user: AuthUser) => void
  logout: () => void
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      accessToken: null,
      refreshToken: null,
      user: null,
      authReady: false,
      sessionVersion: 0,
      setTokens: (accessToken, refreshToken) => set({ accessToken, refreshToken }),
      setUser: (user) => set({ user, authReady: true }),
      logout: () => set(s => ({ accessToken: null, refreshToken: null, user: null, authReady: true, sessionVersion: s.sessionVersion + 1 })),
    }),
    {
      name: 'exam-auth',
      // Never restore cached identity/privileges as an authenticated user.
      partialize: (s) => ({ accessToken: s.accessToken, refreshToken: s.refreshToken }),
      merge: (persisted, current) => {
        const saved = persisted as Partial<AuthState> | undefined
        return { ...current, accessToken: typeof saved?.accessToken === 'string' ? saved.accessToken : null, refreshToken: typeof saved?.refreshToken === 'string' ? saved.refreshToken : null }
      },
    }
  )
)

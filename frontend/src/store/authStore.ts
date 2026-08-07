import { create } from 'zustand'
import { persist } from 'zustand/middleware'

interface AuthState {
  accessToken: string | null
  refreshToken: string | null
  user: { id: string; email: string; fullName: string; role: 'ADMIN' | 'TEACHER' | 'STUDENT' } | null
  setTokens: (access: string, refresh: string) => void
  setUser: (user: AuthState['user']) => void
  logout: () => void
}

export const useAuthStore = create<AuthState>()(
  persist(
    (set) => ({
      accessToken: null,
      refreshToken: null,
      user: null,
      setTokens: (accessToken, refreshToken) => set({ accessToken, refreshToken }),
      setUser: (user) => set({ user }),
      logout: () => {
        set({ accessToken: null, refreshToken: null, user: null })
        window.location.href = '/login'
      },
    }),
    {
      name: 'exam-auth',
      // ponytail: chỉ persist token + user, không persist state phức tạp khác
      partialize: (s) => ({ accessToken: s.accessToken, refreshToken: s.refreshToken, user: s.user }),
    }
  )
)

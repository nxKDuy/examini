import api from '../../lib/api'
import { useAuthStore } from '../../store/authStore'

interface LoginPayload { email: string; password: string }
interface RegisterPayload { email: string; password: string; fullName: string; accountType: 'PERSONAL'; persona: 'STUDENT' | 'TEACHER' }

export const authApi = {
  login: async (data: LoginPayload) => {
    const res = await api.post('/auth/login', data)
    useAuthStore.getState().setTokens(res.data.accessToken, res.data.refreshToken)
    const me = await api.get('/auth/me')
    useAuthStore.getState().setUser(me.data)
    return res.data
  },

  register: async (data: RegisterPayload) => {
    const res = await api.post('/auth/register', data)
    useAuthStore.getState().setTokens(res.data.accessToken, res.data.refreshToken)
    const me = await api.get('/auth/me')
    useAuthStore.getState().setUser(me.data)
    return res.data
  },

  logout: async () => {
    try { await api.post('/auth/logout') } catch { /* ignore */ }
    useAuthStore.getState().logout()
  },
}

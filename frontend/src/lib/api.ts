import axios from 'axios'
import { useAuthStore } from '../store/authStore'

const api = axios.create({
  baseURL: '/',
  headers: { 'Content-Type': 'application/json' },
})

// Inject Bearer token vào mọi request
api.interceptors.request.use((config) => {
  const token = useAuthStore.getState().accessToken
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

// Auto-refresh hoặc logout khi nhận 401
api.interceptors.response.use(
  (res) => res,
  async (error) => {
    const original = error.config
    if (error.response?.status === 401 && !original._retry) {
      // Bỏ qua interceptor 401 nếu đang ở trang login/register để không bị reload
      if (original.url?.includes('/auth/login') || original.url?.includes('/auth/register')) {
        return Promise.reject(error)
      }

      original._retry = true
      const refreshToken = useAuthStore.getState().refreshToken
      if (refreshToken) {
        try {
          const { data } = await axios.post('/auth/refresh', { refreshToken })
          useAuthStore.getState().setTokens(data.accessToken, data.refreshToken)
          original.headers.Authorization = `Bearer ${data.accessToken}`
          return api(original)
        } catch {
          useAuthStore.getState().logout()
        }
      } else {
        useAuthStore.getState().logout()
      }
    }
    return Promise.reject(error)
  }
)

export default api

import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import api from '../../lib/api'
import { useAuthStore, type Persona } from '../../store/authStore'

export interface Profile {
  id: string; email: string; fullName: string; role: 'ADMIN' | Persona; persona: Persona | null; systemPrivilege: 'NONE' | 'ADMIN'
  phone: string | null; school: string | null; bio: string | null; avatarUpdatedAt: string | null
  passwordSet: boolean; googleLinked: boolean; createdAt: string | null; lastLoginAt: string | null
}
export interface ProfileUpdate { fullName: string; phone: string; school: string; bio: string }
interface Tokens { accessToken: string; refreshToken: string }

export interface AdminUser {
  id: string; email: string; fullName: string; role: 'ADMIN' | Persona; persona: Persona | null; systemPrivilege: 'NONE' | 'ADMIN'
  locked: boolean; lockedAt: string | null; googleLinked: boolean; passwordSet: boolean; createdAt: string | null; lastLoginAt: string | null
}
export interface AdminPage { content: AdminUser[]; totalElements: number; totalPages: number; number: number; size: number }
export interface ResetLink { url: string; expiresAt: string; emailed: boolean }

/** Keeps the store's display name in step with the profile (the top bar reads it). */
function syncName(p: Profile) {
  const s = useAuthStore.getState()
  if (s.user && s.user.id === p.id && s.user.fullName !== p.fullName) s.setUser({ ...s.user, fullName: p.fullName })
  return p
}

/** Downscales any picture to a square-ish JPEG ≤ 256 px so uploads stay far below the 300 KB limit. */
export async function toAvatarJpeg(file: Blob, size = 256): Promise<Blob> {
  const bitmap = await createImageBitmap(file)
  const side = Math.min(bitmap.width, bitmap.height)
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = Math.min(size, side)
  canvas.getContext('2d')!.drawImage(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side, 0, 0, canvas.width, canvas.height)
  bitmap.close?.()
  const blob = await new Promise<Blob | null>(r => canvas.toBlob(r, 'image/jpeg', 0.85))
  if (!blob) throw new Error('Không xử lý được ảnh')
  return blob
}

export const accountApi = {
  profile: () => api.get<Profile>('/auth/account').then(r => syncName(r.data)),
  update: (data: ProfileUpdate) => api.patch<Profile>('/auth/account', data).then(r => syncName(r.data)),
  avatar: () => api.get<Blob>('/auth/account/avatar', { responseType: 'blob' }).then(r => r.data),
  setAvatar: (image: Blob) => api.put<Profile>('/auth/account/avatar', image, { headers: { 'Content-Type': image.type || 'image/jpeg' } }).then(r => r.data),
  removeAvatar: () => api.delete<Profile>('/auth/account/avatar').then(r => r.data),
  /** The server signs out other sessions and returns a new one for this browser. */
  changePassword: async (currentPassword: string | null, newPassword: string) => {
    const { data } = await api.post<Tokens>('/auth/account/password', currentPassword ? { currentPassword, newPassword } : { newPassword })
    if (typeof data.accessToken === 'string' && typeof data.refreshToken === 'string') useAuthStore.getState().setTokens(data.accessToken, data.refreshToken)
  },
  linkGoogle: (credential: string) => api.post<Profile>('/auth/account/google', { credential }).then(r => r.data),
  unlinkGoogle: () => api.delete<Profile>('/auth/account/google').then(r => r.data),
  forgot: (email: string) => api.post('/auth/password/forgot', { email: email.trim() }).then(() => undefined),
  reset: (token: string, newPassword: string) => api.post('/auth/password/reset', { token, newPassword }).then(() => undefined),
  adminUsers: (q: string, page: number, size = 20) => api.get<AdminPage>('/auth/admin/users', { params: { q, page, size } }).then(r => r.data),
  adminLock: (id: string, locked: boolean) => api.patch<AdminUser>(`/auth/admin/users/${id}`, { locked }).then(r => r.data),
  adminResetLink: (id: string) => api.post<ResetLink>(`/auth/admin/users/${id}/password-reset`).then(r => r.data),
}

/** The user's own profile, shared by the top bar and the profile page. */
export function useProfile() {
  const userId = useAuthStore(s => s.user?.id)
  return useQuery({ queryKey: ['account', 'profile', userId], queryFn: accountApi.profile, enabled: Boolean(userId), staleTime: 60_000, retry: false })
}

/** Object URL of the user's avatar; refetched whenever the avatar changes (keyed by its update time). */
export function useAvatarUrl(updatedAt: string | null | undefined) {
  const userId = useAuthStore(s => s.user?.id)
  const q = useQuery({ queryKey: ['account', 'avatar', userId, updatedAt], queryFn: accountApi.avatar, enabled: Boolean(userId && updatedAt), staleTime: Infinity, retry: false })
  const [url, setUrl] = useState<string | null>(null)
  useEffect(() => {
    if (!q.data || !updatedAt) { setUrl(null); return }
    const u = URL.createObjectURL(q.data); setUrl(u)
    return () => URL.revokeObjectURL(u)
  }, [q.data, updatedAt])
  return url
}

/** Password rule shared by every password form (the server enforces the same minimum). */
export const passwordProblem = (pwd: string, confirm: string): string | null =>
  pwd.length < 8 ? 'TOO_SHORT' : pwd !== confirm ? 'MISMATCH' : null

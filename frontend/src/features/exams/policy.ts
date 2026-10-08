import type { Policy } from './examApi'

export const DEFAULT_POLICY: Policy = { accessMode: 'ASSIGNED', maxParticipants: 100, maxConcurrentSessions: 50, attemptLimit: 1 }
export const PLATFORM_CONCURRENCY_LIMIT = 100 // backend default `exam.capacity.platform-limit`; the server enforces the real value

export function policyError(p: Policy): string | null {
  const ints = [p.maxParticipants, p.maxConcurrentSessions, p.attemptLimit]
  if (ints.some(v => !Number.isInteger(v) || v < 1)) return 'Các giới hạn phải là số nguyên từ 1 trở lên.'
  if (p.maxConcurrentSessions > p.maxParticipants) return 'Số phiên làm bài đồng thời không được lớn hơn số người tham gia tối đa.'
  if (p.maxConcurrentSessions > PLATFORM_CONCURRENCY_LIMIT) return `Số phiên làm bài đồng thời tối đa là ${PLATFORM_CONCURRENCY_LIMIT}.`
  if (p.accessMode === 'JOIN_CODE' && p.joinCode !== undefined && !/^[A-Za-z0-9_-]{4,50}$/.test(p.joinCode)) return 'Mã tham gia gồm 4 đến 50 ký tự: chữ, số, gạch dưới hoặc gạch ngang.'
  return null
}

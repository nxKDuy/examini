import { showBankTab } from '../banks/bankModel'
import type { InviteRole, Member, Organization, OrgRole, RecipientInput } from './orgApi'
import { t, i18nMap } from '../../lib/i18n'

/** Pure Organization UI rules. They only mirror what the server already enforces so buttons are not offered in vain; the server stays the authority. */

export const ROLE_LABEL: Record<OrgRole, string> = i18nMap({ OWNER: 'Chủ sở hữu', ADMIN: 'Quản trị', MEMBER: 'Thành viên' })
export const MEMBER_STATUS_LABEL: Record<string, string> = i18nMap({ ACTIVE: 'Đang hoạt động', REVOKED: 'Đã bị gỡ', LEFT: 'Đã rời' })
export const INVITATION_STATUS_LABEL: Record<string, string> = i18nMap({ PENDING: 'Đang chờ', ACCEPTED: 'Đã chấp nhận', DECLINED: 'Đã từ chối', REVOKED: 'Đã thu hồi', EXPIRED: 'Hết hạn' })
export const CAPABILITY_LABEL: Record<string, string> = i18nMap({
  BANK_READ: 'Xem ngân hàng câu hỏi của tổ chức', BANK_USE: 'Dùng câu hỏi của tổ chức', BANK_SUBMIT: 'Gửi câu hỏi vào ngân hàng tổ chức',
  BANK_REVIEW: 'Duyệt câu hỏi gửi vào tổ chức', BANK_MANAGE: 'Quản lý ngân hàng tổ chức',
})
export const AUDIT_LABEL: Record<string, string> = i18nMap({
  ORGANIZATION_CREATED: 'Tạo tổ chức', ORGANIZATION_UPDATED: 'Cập nhật thông tin tổ chức', ORGANIZATION_ARCHIVED: 'Lưu trữ tổ chức',
  ROLE_CHANGED: 'Đổi vai trò', REVIEW_GRANT_CHANGED: 'Đổi quyền duyệt', MEMBER_REVOKED: 'Gỡ thành viên', MEMBER_LEFT: 'Thành viên rời tổ chức',
  OWNERSHIP_TRANSFERRED: 'Chuyển quyền sở hữu', INVITATION_CREATED: 'Gửi lời mời', INVITATION_REVOKED: 'Thu hồi lời mời',
  INVITATION_ACCEPTED: 'Chấp nhận lời mời', INVITATION_DECLINED: 'Từ chối lời mời', INVITATION_EXPIRED: 'Lời mời hết hạn',
})

export const isOwner = (role: OrgRole | undefined) => role === 'OWNER'
export const isManager = (role: OrgRole | undefined) => role === 'OWNER' || role === 'ADMIN'
export const isArchived = (org: Pick<Organization, 'status'>) => org.status === 'ARCHIVED'

export type OrgTab = 'members' | 'invitations' | 'audit' | 'settings' | 'bank'
/** Tabs the caller may open, from the real membership. Members never see tabs whose API would answer 403. */
export function tabsFor(role: OrgRole | undefined): OrgTab[] {
  if (!role) return []
  return isOwner(role) ? ['members', 'invitations', 'audit', 'settings'] : isManager(role) ? ['members', 'invitations', 'audit'] : ['members']
}
export const tabsForOrg = (org: Organization): OrgTab[] => [...tabsFor(org.me?.role), ...(showBankTab(org) ? ['bank' as const] : [])]
export const TAB_LABEL: Record<OrgTab, string> = i18nMap({ bank: 'Ngân hàng câu hỏi', members: 'Thành viên', invitations: 'Lời mời', audit: 'Nhật ký', settings: 'Cài đặt' })
export const parseTab = (value: string | null, allowed: OrgTab[]): OrgTab => (allowed.includes(value as OrgTab) ? (value as OrgTab) : 'members')

export interface MemberAbilities { changeRole: boolean; toggleReview: boolean; remove: boolean; any: boolean; note: string | null }
/** What the viewer may do to one member. OWNER manages ADMIN/MEMBER; ADMIN only removes or grants review to MEMBER; nobody edits an OWNER or self here. */
export function memberAbilities(viewer: { role?: OrgRole; membershipId?: string }, target: Pick<Member, 'role' | 'status' | 'membershipId'>, orgArchived: boolean): MemberAbilities {
  const none = (note: string | null): MemberAbilities => ({ changeRole: false, toggleReview: false, remove: false, any: false, note })
  if (orgArchived) return none(t('Tổ chức đã lưu trữ nên chỉ xem.'))
  if (target.status !== 'ACTIVE') return none(t('Thành viên này không còn hoạt động.'))
  if (target.role === 'OWNER') return none(t('Chủ sở hữu chỉ đổi được qua chuyển quyền sở hữu (tab Cài đặt).'))
  if (target.membershipId === viewer.membershipId) return none(t('Muốn rời tổ chức, dùng nút Rời tổ chức.'))
  if (viewer.role === 'OWNER') return { changeRole: true, toggleReview: target.role === 'MEMBER', remove: true, any: true, note: null }
  if (viewer.role === 'ADMIN') {
    if (target.role !== 'MEMBER') return none(t('Quản trị chỉ quản lý thành viên thường.'))
    return { changeRole: false, toggleReview: true, remove: true, any: true, note: t('Quản trị không đổi được vai trò.') }
  }
  return none(null)
}

export const inviteRolesFor = (role: OrgRole | undefined): InviteRole[] => (role === 'OWNER' ? ['MEMBER', 'ADMIN'] : role === 'ADMIN' ? ['MEMBER'] : [])
export const canManageOrg = (org: Pick<Organization, 'status' | 'me'>) => !isArchived(org) && isOwner(org.me?.role)
export const canInvite = (org: Pick<Organization, 'status' | 'me'>) => !isArchived(org) && isManager(org.me?.role)

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/
/** One box, two kinds of input: a user id or an exact e-mail. The e-mail is sent exactly as typed (the server matches the stored value, no normalising here). */
export function parseRecipient(raw: string): RecipientInput | null {
  const v = raw.trim()
  if (UUID.test(v)) return { userId: v }
  if (v.length <= 254 && EMAIL.test(v)) return { email: v }
  return null
}

export const shortId = (id: string) => id.slice(0, 8)
export function memberName(m: Pick<Member, 'displayName' | 'userId'>): { text: string; fallback: boolean } {
  const n = m.displayName?.trim()
  return n ? { text: n, fallback: false } : { text: t('Thành viên {0}', { 0: shortId(m.userId) }), fallback: true }
}
export function recipientName(i: { recipientDisplayName?: string; recipientUserId: string }): { text: string; fallback: boolean } {
  const n = i.recipientDisplayName?.trim()
  return n ? { text: n, fallback: false } : { text: t('Người dùng {0}', { 0: shortId(i.recipientUserId) }), fallback: true }
}

/** Candidates for ownership transfer: active ADMIN/MEMBER other than the caller. */
export const transferTargets = (members: Member[], viewerMembershipId: string | undefined) =>
  members.filter(m => m.status === 'ACTIVE' && m.role !== 'OWNER' && m.membershipId !== viewerMembershipId)

/** Typed confirmation for irreversible actions: exact name, trimmed. */
export const nameMatches = (typed: string, name: string) => typed.trim() === name.trim() && name.trim() !== ''

/** 409 from a stale org/member revision: the detail names the current revision. Other 409s (lifecycle, last owner) are ordinary errors. */
export function isStaleRevision(error: unknown): boolean {
  const e = error as { response?: { status?: number; data?: { detail?: string } } }
  return e?.response?.status === 409 && /^Dữ liệu đã thay đổi/.test(e.response.data?.detail ?? '')
}

export type MemberEdit = { role: InviteRole | OrgRole; canReview: boolean }
/** Only changed fields are sent; canReview only exists for a MEMBER, so promoting to ADMIN never sends a stale grant. */
export function memberPatch(member: Pick<Member, 'role' | 'canReview' | 'revision'>, edit: MemberEdit): { role?: InviteRole; canReview?: boolean; revision: number } | null {
  const body: { role?: InviteRole; canReview?: boolean; revision: number } = { revision: member.revision }
  if (edit.role !== member.role && edit.role !== 'OWNER') body.role = edit.role
  const review = edit.role === 'MEMBER' ? edit.canReview : false
  if (review !== member.canReview && edit.role === 'MEMBER') body.canReview = review
  return body.role === undefined && body.canReview === undefined ? null : body
}

export const expiresText = (iso: string | undefined, nowMs: number): string => {
  if (!iso) return ''
  const ms = Date.parse(iso) - nowMs
  if (Number.isNaN(ms)) return ''
  if (ms <= 0) return t('đã hết hạn')
  const days = Math.floor(ms / 86_400_000)
  return days >= 1 ? t('còn {0} ngày', { 0: days }) : t('hết hạn trong hôm nay')
}

import { BookOpenCheck, Building2, Users, CalendarDays, ClipboardCheck, FileText, LayoutDashboard, ListChecks, ShieldCheck, TrendingUp, BarChart3, type LucideIcon } from 'lucide-react'
import { hasPersona, isSystemAdmin, type AuthUser } from '../../store/authStore'
import { t, i18nMap } from '../../lib/i18n'

/** `shortLabel` is the dock tile text (a phone tile is ~64px wide); `label` stays the full name and the accessible name. */
export interface NavItem { to: string; label: string; shortLabel?: string; icon: LucideIcon; end?: boolean }
export interface NavGroup { label?: string; items: NavItem[] }

export type Audience = 'ADMIN' | 'TEACHER' | 'STUDENT'

/** Global Admin UX follows systemPrivilege; persona UX follows persona. TEACHER never implies ADMIN. */
export function audienceOf(user: AuthUser | null): Audience {
  if (isSystemAdmin(user)) return 'ADMIN'
  return hasPersona(user, 'TEACHER') ? 'TEACHER' : 'STUDENT'
}

export const AUDIENCE_LABEL: Record<Audience, string> = i18nMap({ ADMIN: 'Quản trị hệ thống', TEACHER: 'Giáo viên', STUDENT: 'Học sinh / Sinh viên' })

export function navigationFor(audience: Audience): NavGroup[] {
  const publicBank: NavItem = { to: '/public-bank', label: t('Ngân hàng công khai'), shortLabel: t('Công khai'), icon: BookOpenCheck }
  const overview: NavItem = { to: '/dashboard', label: t('Tổng quan'), icon: LayoutDashboard }
  const organizations: NavItem = { to: '/organizations', label: t('Tổ chức'), icon: Building2 }
  if (audience === 'STUDENT') {
    return [{ items: [overview, publicBank, { to: '/calendar', label: t('Lịch của tôi'), shortLabel: t('Lịch'), icon: CalendarDays }, { to: '/my-exams', label: t('Kỳ thi của tôi'), shortLabel: t('Kỳ thi'), icon: ListChecks }, { to: '/progress', label: t('Tiến bộ'), icon: TrendingUp }, organizations] }]
  }
  if (audience === 'TEACHER') {
    return [
      { items: [overview] },
      { label: t('Giảng dạy'), items: [
        { to: '/calendar', label: t('Lịch thi'), icon: CalendarDays },
        { to: '/questions', label: t('Kho cá nhân'), shortLabel: 'Kho', icon: BookOpenCheck },
        publicBank,
        { to: '/exams', label: t('Đề thi'), icon: FileText },
        { to: '/grading', label: t('Chấm bài'), icon: ClipboardCheck },
        organizations,
      ] },
    ]
  }
  return [
    { items: [overview] },
    { label: t('Quản trị'), items: [{ to: '/admin/stats', label: t('Thống kê'), icon: BarChart3 }, { to: '/admin/users', label: t('Người dùng'), icon: Users }, { to: '/questions', label: t('Câu hỏi'), icon: ShieldCheck }, publicBank, organizations] },
    { label: t('Khảo thí'), items: [
      { to: '/calendar', label: t('Lịch thi'), icon: CalendarDays },
      { to: '/exams', label: t('Đề thi'), icon: FileText },
      { to: '/grading', label: t('Chấm bài'), icon: ClipboardCheck },
    ] },
  ]
}

/** Every destination of an audience, flat and without duplicates: the topbar quick links on tablet/desktop. */
export function navigationItems(audience: Audience): NavItem[] {
  const seen = new Set<string>()
  return navigationFor(audience).flatMap(group => group.items).filter(item => (seen.has(item.to) ? false : (seen.add(item.to), true)))
}

/** Phone dock: at most 5 tiles. Up to 5 destinations all fit; otherwise the audience's priority items take 4 tiles and the rest go behind "Khác". One source for both. */
export const DOCK_SLOTS = 5
export const DOCK_PRIORITY: Record<Audience, string[]> = {
  STUDENT: ['/dashboard', '/my-exams', '/progress', '/calendar'],
  TEACHER: ['/dashboard', '/calendar', '/exams', '/grading'],
  ADMIN: ['/dashboard', '/questions', '/exams', '/grading'],
}
export function dockFor(audience: Audience): { dock: NavItem[]; more: NavItem[] } {
  const items = navigationItems(audience)
  if (items.length <= DOCK_SLOTS) return { dock: items, more: [] }
  const dock = DOCK_PRIORITY[audience].map(to => items.find(item => item.to === to)).filter((item): item is NavItem => !!item).slice(0, DOCK_SLOTS - 1)
  return { dock, more: items.filter(item => !dock.includes(item)) }
}

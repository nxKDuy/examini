import { BookOpenCheck, Building2, CalendarDays, ClipboardCheck, FileText, LayoutDashboard, ListChecks, ShieldCheck, type LucideIcon } from 'lucide-react'
import { hasPersona, isSystemAdmin, type AuthUser } from '../../store/authStore'

/** `shortLabel` is the dock tile text (a phone tile is ~64px wide); `label` stays the full name and the accessible name. */
export interface NavItem { to: string; label: string; shortLabel?: string; icon: LucideIcon; end?: boolean }
export interface NavGroup { label?: string; items: NavItem[] }

export type Audience = 'ADMIN' | 'TEACHER' | 'STUDENT'

/** Global Admin UX follows systemPrivilege; persona UX follows persona. TEACHER never implies ADMIN. */
export function audienceOf(user: AuthUser | null): Audience {
  if (isSystemAdmin(user)) return 'ADMIN'
  return hasPersona(user, 'TEACHER') ? 'TEACHER' : 'STUDENT'
}

export const AUDIENCE_LABEL: Record<Audience, string> = { ADMIN: 'Quản trị hệ thống', TEACHER: 'Giáo viên', STUDENT: 'Học sinh / Sinh viên' }

export function navigationFor(audience: Audience): NavGroup[] {
  const publicBank: NavItem = { to: '/public-bank', label: 'Ngân hàng công khai', shortLabel: 'Công khai', icon: BookOpenCheck }
  const overview: NavItem = { to: '/dashboard', label: 'Tổng quan', icon: LayoutDashboard }
  const organizations: NavItem = { to: '/organizations', label: 'Tổ chức', icon: Building2 }
  if (audience === 'STUDENT') {
    return [{ items: [overview, publicBank, { to: '/calendar', label: 'Lịch của tôi', shortLabel: 'Lịch', icon: CalendarDays }, { to: '/my-exams', label: 'Kỳ thi của tôi', shortLabel: 'Kỳ thi', icon: ListChecks }, organizations] }]
  }
  if (audience === 'TEACHER') {
    return [
      { items: [overview] },
      { label: 'Giảng dạy', items: [
        { to: '/calendar', label: 'Lịch thi', icon: CalendarDays },
        { to: '/questions', label: 'Kho cá nhân', shortLabel: 'Kho', icon: BookOpenCheck },
        publicBank,
        { to: '/exams', label: 'Đề thi', icon: FileText },
        { to: '/grading', label: 'Chấm bài', icon: ClipboardCheck },
        organizations,
      ] },
    ]
  }
  return [
    { items: [overview] },
    { label: 'Quản trị', items: [{ to: '/questions', label: 'Câu hỏi', icon: ShieldCheck }, publicBank, organizations] },
    { label: 'Khảo thí', items: [
      { to: '/calendar', label: 'Lịch thi', icon: CalendarDays },
      { to: '/exams', label: 'Đề thi', icon: FileText },
      { to: '/grading', label: 'Chấm bài', icon: ClipboardCheck },
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
  STUDENT: [],
  TEACHER: ['/dashboard', '/calendar', '/exams', '/grading'],
  ADMIN: ['/dashboard', '/questions', '/exams', '/grading'],
}
export function dockFor(audience: Audience): { dock: NavItem[]; more: NavItem[] } {
  const items = navigationItems(audience)
  if (items.length <= DOCK_SLOTS) return { dock: items, more: [] }
  const dock = DOCK_PRIORITY[audience].map(to => items.find(item => item.to === to)).filter((item): item is NavItem => !!item).slice(0, DOCK_SLOTS - 1)
  return { dock, more: items.filter(item => !dock.includes(item)) }
}

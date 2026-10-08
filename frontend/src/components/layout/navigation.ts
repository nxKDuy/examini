import { BookOpenCheck, Building2, CalendarDays, ClipboardCheck, FileText, LayoutDashboard, ListChecks, ShieldCheck, type LucideIcon } from 'lucide-react'
import { hasPersona, isSystemAdmin, type AuthUser } from '../../store/authStore'

export interface NavItem { to: string; label: string; icon: LucideIcon; end?: boolean }
export interface NavGroup { label?: string; items: NavItem[] }

export type Audience = 'ADMIN' | 'TEACHER' | 'STUDENT'

/** Global Admin UX follows systemPrivilege; persona UX follows persona. TEACHER never implies ADMIN. */
export function audienceOf(user: AuthUser | null): Audience {
  if (isSystemAdmin(user)) return 'ADMIN'
  return hasPersona(user, 'TEACHER') ? 'TEACHER' : 'STUDENT'
}

export const AUDIENCE_LABEL: Record<Audience, string> = { ADMIN: 'Quản trị hệ thống', TEACHER: 'Giáo viên', STUDENT: 'Học sinh / Sinh viên' }

export function navigationFor(audience: Audience): NavGroup[] {
  const publicBank: NavItem = { to: '/public-bank', label: 'Ngân hàng công khai', icon: BookOpenCheck }
  const overview: NavItem = { to: '/dashboard', label: 'Tổng quan', icon: LayoutDashboard }
  if (audience === 'STUDENT') {
    return [{ items: [overview, publicBank, { to: '/calendar', label: 'Lịch của tôi', icon: CalendarDays }, { to: '/my-exams', label: 'Kỳ thi của tôi', icon: ListChecks }, { to: '/organizations', label: 'Tổ chức', icon: Building2 }] }]
  }
  if (audience === 'TEACHER') {
    return [
      { items: [overview] },
      { label: 'Giảng dạy', items: [
        { to: '/calendar', label: 'Lịch thi', icon: CalendarDays },
        { to: '/questions', label: 'Kho cá nhân', icon: BookOpenCheck },
        publicBank,
        { to: '/exams', label: 'Đề thi', icon: FileText },
        { to: '/grading', label: 'Chấm bài', icon: ClipboardCheck },
        { to: '/organizations', label: 'Tổ chức', icon: Building2 },
      ] },
    ]
  }
  return [
    { items: [overview] },
    { label: 'Quản trị', items: [{ to: '/questions', label: 'Câu hỏi', icon: ShieldCheck }, publicBank, { to: '/organizations', label: 'Tổ chức', icon: Building2 }] },
    { label: 'Khảo thí', items: [
      { to: '/calendar', label: 'Lịch thi', icon: CalendarDays },
      { to: '/exams', label: 'Đề thi', icon: FileText },
      { to: '/grading', label: 'Chấm bài', icon: ClipboardCheck },
    ] },
  ]
}

import { NavLink } from 'react-router-dom'
import { useAuthStore } from '../../store/authStore'
import { LayoutDashboard, BookOpen, FileText, CheckSquare, Users, BarChart2 } from 'lucide-react'

const NAV_ITEMS = {
  ADMIN: [
    { to: '/dashboard', icon: LayoutDashboard, label: 'Tổng quan' },
    { to: '/questions', icon: BookOpen,         label: 'Ngân hàng câu hỏi' },
    { to: '/exams',    icon: FileText,           label: 'Đề thi' },
    { to: '/grading',  icon: CheckSquare,        label: 'Chấm bài' },
    { to: '/users',    icon: Users,              label: 'Quản lý người dùng' },
    { to: '/analytics',icon: BarChart2,          label: 'Thống kê' },
  ],
  TEACHER: [
    { to: '/dashboard', icon: LayoutDashboard, label: 'Tổng quan' },
    { to: '/questions', icon: BookOpen,         label: 'Ngân hàng câu hỏi' },
    { to: '/exams',    icon: FileText,           label: 'Đề thi' },
    { to: '/grading',  icon: CheckSquare,        label: 'Chấm bài' },
    { to: '/analytics',icon: BarChart2,          label: 'Thống kê' },
  ],
  STUDENT: [
    { to: '/dashboard', icon: LayoutDashboard, label: 'Tổng quan' },
    { to: '/exams',    icon: FileText,           label: 'Bài thi của tôi' },
  ],
}

export default function Sidebar() {
  const { user } = useAuthStore()
  const items = NAV_ITEMS[user?.role ?? 'STUDENT']

  return (
    <aside style={{
      width: 240, 
      flexShrink: 0, 
      height: 'calc(100dvh - 64px)', /* Trừ đi height của TopNavbar */
      position: 'sticky', 
      top: 64,
      background: 'var(--color-surface-900)', /* Hơi xám nhẹ để tách biệt với vùng nội dung trắng */
      borderRight: '1px solid var(--color-surface-700)',
      display: 'flex', 
      flexDirection: 'column', 
      padding: '24px 12px',
    }}>
      <div style={{ padding: '0 12px 12px', fontSize: '0.75rem', fontWeight: 600, color: 'var(--color-surface-600)', textTransform: 'uppercase', letterSpacing: '0.05em' }}>
        Menu chính
      </div>
      
      {/* Nav */}
      <nav style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 4 }} aria-label="Main navigation">
        {items.map(({ to, icon: Icon, label }) => (
          <NavLink key={to} to={to}
            className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}>
            <Icon size={18} style={{ opacity: 0.8 }} />
            <span style={{ flex: 1 }}>{label}</span>
          </NavLink>
        ))}
      </nav>
    </aside>
  )
}

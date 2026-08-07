import { useAuthStore } from '../../store/authStore'
import { authApi } from '../../features/auth/authApi'
import { useNavigate } from 'react-router-dom'
import { Search, User, LogOut, Brain } from 'lucide-react'

export default function TopNavbar() {
  const { user } = useAuthStore()
  const navigate = useNavigate()

  const handleLogout = async () => {
    await authApi.logout()
    navigate('/login')
  }

  return (
    <header style={{
      height: 64,
      background: 'var(--color-surface-950)',
      borderBottom: '1px solid var(--color-surface-700)',
      display: 'flex',
      alignItems: 'center',
      justifyContent: 'space-between',
      padding: '0 24px',
      position: 'sticky',
      top: 0,
      zIndex: 50,
    }}>
      {/* Logo & Brand */}
      <div style={{ display: 'flex', alignItems: 'center' }}>
        <span style={{ 
          fontFamily: "'Pacifico', cursive", 
          fontSize: '1.75rem', 
          color: 'var(--color-primary-500)',
          letterSpacing: '0.02em',
          lineHeight: 1
        }}>
          examini
        </span>
      </div>

      {/* Center Search (Optional/Placeholder for Minimal UI) */}
      <div style={{ flex: 1, maxWidth: 400, margin: '0 24px', position: 'relative' }}>
        <Search size={16} style={{ position: 'absolute', left: 12, top: 11, color: 'var(--color-surface-400)' }} />
        <input 
          type="text" 
          placeholder="Tìm kiếm..." 
          className="input-field" 
          style={{ paddingLeft: 36, height: 38, borderRadius: 20 }}
        />
      </div>

      {/* Profile & Actions */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end' }}>
          <span style={{ fontSize: '0.875rem', fontWeight: 600, color: 'var(--color-surface-50)' }}>
            {user?.fullName || 'Người dùng'}
          </span>
          <span className={`badge ${user?.role === 'ADMIN' ? 'badge-primary' : user?.role === 'TEACHER' ? 'badge-green' : 'badge-gray'}`} style={{ marginTop: 2, fontSize: '0.65rem' }}>
            {user?.role || 'STUDENT'}
          </span>
        </div>
        
        <div style={{ 
          width: 38, height: 38, 
          borderRadius: '50%', 
          background: 'var(--color-surface-800)', 
          border: '1px solid var(--color-surface-700)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
          color: 'var(--color-surface-400)'
        }}>
          <User size={20} />
        </div>

        {/* Divider */}
        <div style={{ width: 1, height: 24, background: 'var(--color-surface-700)' }} />

        <button 
          onClick={handleLogout}
          style={{ 
            background: 'none', border: 'none', cursor: 'pointer', 
            color: 'var(--color-surface-600)', display: 'flex', alignItems: 'center' 
          }}
          title="Đăng xuất"
        >
          <LogOut size={20} />
        </button>
      </div>
    </header>
  )
}

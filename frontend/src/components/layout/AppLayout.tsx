import { Outlet } from 'react-router-dom'
import Sidebar from './Sidebar'
import TopNavbar from './TopNavbar'

export default function AppLayout() {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: '100dvh' }}>
      <TopNavbar />
      <div style={{ display: 'flex', flex: 1 }}>
        <Sidebar />
        <main style={{ flex: 1, overflow: 'auto', minWidth: 0, background: 'transparent' }}>
          <div className="page-enter" style={{ padding: '32px 40px', maxWidth: 1200, margin: '0 auto' }}>
            <Outlet />
          </div>
        </main>
      </div>
    </div>
  )
}

import { Link } from 'react-router-dom'
import { hasPersona, isSystemAdmin, useAuthStore } from '../../store/authStore'

export default function DashboardPage() {
  const user = useAuthStore(s => s.user)
  const admin = isSystemAdmin(user)
  const teacher = hasPersona(user, 'TEACHER')
  return (
    <section>
      <h1>Xin chào, {user?.fullName}!</h1>
      <h2>{admin ? 'Quản trị hệ thống' : teacher ? 'Không gian giáo viên' : 'Không gian học sinh / sinh viên'}</h2>
      <nav aria-label="Dashboard navigation" style={{ display: 'flex', gap: 24, marginTop: 24 }}>
        {admin && <Link to="/users">Quản lý người dùng</Link>}
        {(teacher || admin) ? <>
          <Link to="/questions">Ngân hàng câu hỏi</Link>
          <Link to="/exams">Quản lý đề thi</Link>
          <Link to="/grading">Chấm bài</Link>
        </> : <Link to="/exam/lobby/discover">Bài thi của tôi / Kỳ thi có thể tham gia</Link>}
      </nav>
      <p style={{ marginTop: 24 }}>Các thao tác trên từng tài nguyên phụ thuộc quyền sở hữu và phân công của bạn.</p>
    </section>
  )
}

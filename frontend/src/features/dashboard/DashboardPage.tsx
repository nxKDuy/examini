import { useAuthStore } from '../../store/authStore'
import { BookOpen, FileText, Activity, Users, Clock, CheckCircle, AlertCircle, Play } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import NotificationBoard from './NotificationBoard'

export default function DashboardPage() {
  const { user } = useAuthStore()

  if (user?.role === 'STUDENT') {
    return <StudentDashboard />
  }

  return <TeacherDashboard />
}

function StudentDashboard() {
  const { user } = useAuthStore()
  const navigate = useNavigate()

  // Mock data for student exams
  const exams = [
    { id: '1', name: 'Kiểm tra Giữa kỳ Môn Toán 12', timeLimit: 45, totalQuestions: 40, status: 'ACTIVE' },
    { id: '2', name: 'Đề khảo sát năng lực Tiếng Anh', timeLimit: 60, totalQuestions: 50, status: 'UPCOMING', startsIn: '2 ngày nữa' },
    { id: '3', name: 'Bài tập cuối khóa Vật Lý', timeLimit: 90, totalQuestions: 60, status: 'SUBMITTED', score: 8.5 },
    { id: '4', name: 'Kiểm tra 15p Hóa Học', timeLimit: 15, totalQuestions: 10, status: 'MISSED' }
  ]

  return (
    <div className="page-enter">
      <h1 style={{ fontSize: '1.75rem', fontWeight: 700, color: 'var(--color-surface-50)',
        letterSpacing: '-0.02em', marginBottom: '8px' }}>
        Xin chào, {user?.fullName}!
      </h1>
      <p style={{ color: 'var(--color-surface-400)', fontSize: '0.9375rem', marginBottom: '32px' }}>
        Hôm nay bạn có 1 bài thi đang chờ. Cố gắng lên nhé!
      </p>

      {/* Stats Grid */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(200px, 1fr))', gap: '20px', marginBottom: 40 }}>
        <StatCard title="Kỳ thi đã hoàn thành" value="12" icon={CheckCircle} color="green" />
        <StatCard title="Điểm trung bình" value="8.4" icon={Activity} color="indigo" />
      </div>

      {/* Main layout: Exam list + Notification sidebar */}
      <div style={{ display: 'grid', gridTemplateColumns: '1fr 380px', gap: 28, alignItems: 'start' }}>
        {/* Left: Exam cards */}
        <div>
          <h2 style={{ fontSize: '1.25rem', fontWeight: 600, color: 'var(--color-surface-50)', marginBottom: '20px' }}>
            Kỳ thi của tôi
          </h2>
        
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: '20px' }}>
            {exams.map(exam => (
              <div key={exam.id} className="clean-card" style={{ display: 'flex', flexDirection: 'column', overflow: 'hidden', 
                opacity: exam.status === 'MISSED' ? 0.6 : 1, transition: 'all 0.2s' }}>
                
                <div style={{ padding: '20px', borderBottom: '1px solid var(--color-surface-700)', flex: 1 }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: 12 }}>
                    <span className={`badge ${
                      exam.status === 'ACTIVE' ? 'badge-primary' : 
                      exam.status === 'UPCOMING' ? 'badge-yellow' : 
                      exam.status === 'SUBMITTED' ? 'badge-green' : 'badge-gray'
                    }`}>
                      {exam.status === 'ACTIVE' ? 'Đang mở' : 
                       exam.status === 'UPCOMING' ? 'Sắp diễn ra' : 
                       exam.status === 'SUBMITTED' ? 'Đã nộp bài' : 'Đã lỡ'}
                    </span>
                  </div>
                  <h3 style={{ fontSize: '1.1rem', fontWeight: 600, color: 'var(--color-surface-50)', marginBottom: 8, lineHeight: 1.4 }}>
                    {exam.name}
                  </h3>
                  <div style={{ display: 'flex', gap: 16, color: 'var(--color-surface-400)', fontSize: '0.875rem' }}>
                    <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}><Clock size={14} /> {exam.timeLimit} phút</span>
                    <span style={{ display: 'flex', alignItems: 'center', gap: 4 }}><FileText size={14} /> {exam.totalQuestions} câu</span>
                  </div>
                </div>
                
                <div style={{ padding: '16px 20px', background: 'var(--color-surface-900)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  {exam.status === 'ACTIVE' && (
                    <button className="btn-primary" style={{ width: '100%', display: 'flex', justifyContent: 'center', animation: 'pulse 2s infinite' }}
                      onClick={() => navigate(`/exam/lobby/${exam.id}`)}>
                      <Play size={16} style={{ marginRight: 8 }} /> Vào thi ngay
                    </button>
                  )}
                  {exam.status === 'UPCOMING' && (
                    <span style={{ color: 'var(--color-surface-400)', fontSize: '0.875rem', display: 'flex', alignItems: 'center', gap: 6 }}>
                      <AlertCircle size={16} /> Mở sau: <strong>{exam.startsIn}</strong>
                    </span>
                  )}
                  {exam.status === 'SUBMITTED' && (
                    <div style={{ display: 'flex', justifyContent: 'space-between', width: '100%', alignItems: 'center' }}>
                      <span style={{ fontSize: '0.9rem', color: 'var(--color-surface-300)' }}>Điểm số: <strong style={{ color: 'var(--color-success)', fontSize: '1.1rem' }}>{exam.score}</strong></span>
                      <button className="btn-ghost" style={{ padding: '4px 8px' }} onClick={() => navigate(`/exam/result/${exam.id}`)}>Xem chi tiết</button>
                    </div>
                  )}
                  {exam.status === 'MISSED' && (
                    <span style={{ color: 'var(--color-error)', fontSize: '0.875rem' }}>Đã quá hạn tham gia</span>
                  )}
                </div>
              </div>
            ))}
          </div>
        </div>

        {/* Right: Notification sidebar */}
        <div style={{ position: 'sticky', top: 24 }}>
          <NotificationBoard />
        </div>
      </div>

      <style>{`
        @keyframes pulse {
          0% { box-shadow: 0 0 0 0 rgba(240, 78, 56, 0.4); }
          70% { box-shadow: 0 0 0 10px rgba(240, 78, 56, 0); }
          100% { box-shadow: 0 0 0 0 rgba(240, 78, 56, 0); }
        }
      `}</style>
    </div>
  )
}


function TeacherDashboard() {
  const { user } = useAuthStore()

  return (
    <div className="page-enter">
      <h1 style={{ fontSize: '1.75rem', fontWeight: 700, color: 'var(--color-surface-50)',
        letterSpacing: '-0.02em', marginBottom: '8px' }}>
        Xin chào, {user?.fullName}!
      </h1>
      <p style={{ color: 'var(--color-surface-400)', fontSize: '0.9375rem', marginBottom: '32px' }}>
        Chào mừng bạn quay lại hệ thống examini.
      </p>

      {/* Stats Grid */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(240px, 1fr))', gap: '20px' }}>
        <StatCard title="Tổng số câu hỏi" value="1,248" icon={BookOpen} color="indigo" />
        <StatCard title="Đề thi đã tạo" value="42" icon={FileText} color="green" />
        <StatCard title="Số lượt thi" value="8,932" icon={Activity} color="yellow" />
        <StatCard title="Học sinh" value="450" icon={Users} color="info" />
      </div>

      {/* Placeholder cho biểu đồ hoặc activity feed */}
      <div className="clean-card" style={{ marginTop: '32px', padding: '24px', minHeight: '300px' }}>
        <h2 style={{ fontSize: '1.125rem', fontWeight: 600, color: 'var(--color-surface-50)', marginBottom: '16px' }}>
          Hoạt động gần đây
        </h2>
        <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
          <div className="skeleton" style={{ height: '60px', borderRadius: 'var(--radius-md)' }} />
          <div className="skeleton" style={{ height: '60px', borderRadius: 'var(--radius-md)', opacity: 0.7 }} />
          <div className="skeleton" style={{ height: '60px', borderRadius: 'var(--radius-md)', opacity: 0.4 }} />
        </div>
      </div>
    </div>
  )
}

function StatCard({ title, value, icon: Icon, color }: { title: string, value: string, icon: any, color: string }) {
  const bgMap: Record<string, string> = {
    indigo: 'rgba(99,102,241,0.1)',
    green: 'rgba(34,197,94,0.1)',
    yellow: 'rgba(245,158,11,0.1)',
    info: 'rgba(56,189,248,0.1)',
  }
  const iconColorMap: Record<string, string> = {
    indigo: 'var(--color-primary-400)',
    green: '#4ade80',
    yellow: '#fbbf24',
    info: '#38bdf8',
  }

  return (
    <div className="clean-card" style={{ padding: '20px', display: 'flex', alignItems: 'flex-start', gap: '16px' }}>
      <div style={{ background: bgMap[color], padding: '12px', borderRadius: '12px' }}>
        <Icon size={24} color={iconColorMap[color]} />
      </div>
      <div>
        <div style={{ color: 'var(--color-surface-400)', fontSize: '0.875rem', fontWeight: 500, marginBottom: '4px' }}>
          {title}
        </div>
        <div style={{ color: 'var(--color-surface-50)', fontSize: '1.5rem', fontWeight: 700, letterSpacing: '-0.02em' }}>
          {value}
        </div>
      </div>
    </div>
  )
}

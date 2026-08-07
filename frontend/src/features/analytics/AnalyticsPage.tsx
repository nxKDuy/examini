import { BarChart, Target, Users, CheckCircle2, Activity, Clock } from 'lucide-react'

export default function AnalyticsPage() {
  return (
    <div style={{ paddingBottom: 40 }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 32 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <div style={{ background: 'var(--color-primary-50)', borderRadius: 8, padding: 8 }}>
            <BarChart size={28} color="var(--color-primary-600)" />
          </div>
          <div>
            <h1 style={{ fontSize: '1.5rem', fontWeight: 700, color: 'var(--color-surface-50)', letterSpacing: '-0.02em', margin: 0 }}>
              Thống kê & Phân tích
            </h1>
            <p style={{ color: 'var(--color-surface-400)', fontSize: '0.875rem', margin: 0 }}>
              Tổng quan tình hình học tập và thi cử
            </p>
          </div>
        </div>
        
        <select className="input-field" style={{ width: 200, height: 40 }}>
          <option>Tất cả môn học</option>
          <option>Toán học</option>
          <option>Vật lý</option>
        </select>
      </div>

      {/* Bento Grid Layout */}
      <div style={{
        display: 'grid',
        gridTemplateColumns: 'repeat(4, 1fr)',
        gridTemplateRows: 'auto auto',
        gap: 20,
      }}>
        
        {/* Main Metric: Average Score (Span 2 cols) */}
        <div className="clean-card" style={{ gridColumn: 'span 2', padding: 24, display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, color: 'var(--color-surface-400)', fontWeight: 500 }}>
            <Target size={20} color="var(--color-primary-400)" />
            Điểm trung bình hệ thống
          </div>
          <div style={{ marginTop: 24, display: 'flex', alignItems: 'baseline', gap: 12 }}>
            <span style={{ fontSize: '3.5rem', fontWeight: 900, color: 'var(--color-surface-50)', letterSpacing: '-0.04em', lineHeight: 1 }}>
              7.8
            </span>
            <span style={{ fontSize: '1rem', color: 'var(--color-surface-400)', fontWeight: 500 }}>/ 10</span>
          </div>
          <div style={{ marginTop: 16, display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: '0.85rem', color: 'var(--color-success)', background: '#ecfdf5', padding: '4px 8px', borderRadius: 16, width: 'fit-content', fontWeight: 600 }}>
             +0.4 điểm so với tháng trước
          </div>
        </div>

        {/* Total Students */}
        <div className="clean-card" style={{ padding: 24, display: 'flex', flexDirection: 'column', justifyContent: 'space-between' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, color: 'var(--color-surface-400)', fontWeight: 500 }}>
             <Users size={20} color="#38bdf8" /> Tổng thí sinh
          </div>
          <div style={{ marginTop: 24, fontSize: '2.5rem', fontWeight: 800, color: 'var(--color-surface-50)', letterSpacing: '-0.03em' }}>
            1,248
          </div>
          <div style={{ marginTop: 8, fontSize: '0.85rem', color: 'var(--color-surface-400)' }}>
            Đã tham gia ít nhất 1 bài thi
          </div>
        </div>

        {/* Pass Rate */}
        <div className="clean-card" style={{ padding: 24, display: 'flex', flexDirection: 'column', justifyContent: 'space-between', background: 'var(--color-primary-500)', borderColor: 'var(--color-primary-600)' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, color: 'rgba(255,255,255,0.8)', fontWeight: 500 }}>
             <CheckCircle2 size={20} color="#fff" /> Tỷ lệ đỗ
          </div>
          <div style={{ marginTop: 24, fontSize: '2.5rem', fontWeight: 800, color: '#fff', letterSpacing: '-0.03em' }}>
            86%
          </div>
          <div style={{ marginTop: 8, fontSize: '0.85rem', color: 'rgba(255,255,255,0.8)' }}>
            Trung bình toàn trường
          </div>
        </div>

        {/* Exams Taken Chart (Span 3 cols) */}
        <div className="clean-card" style={{ gridColumn: 'span 3', padding: 24 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 24 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 12, color: 'var(--color-surface-400)', fontWeight: 500 }}>
               <Activity size={20} color="#fbbf24" /> Lượt làm bài thi (Tháng này)
            </div>
          </div>
          
          {/* Fake Chart CSS Graphics */}
          <div style={{ height: 160, display: 'flex', alignItems: 'flex-end', gap: 16, paddingTop: 20 }}>
            {[30, 45, 25, 60, 90, 75, 40, 55, 80, 100, 65, 45].map((h, i) => (
              <div key={i} style={{ flex: 1, background: h > 80 ? 'var(--color-primary-500)' : 'var(--color-surface-700)', height: `${h}%`, borderRadius: '4px 4px 0 0', position: 'relative', transition: 'all 0.2s', cursor: 'pointer' }}
                onMouseEnter={e => e.currentTarget.style.opacity = '0.8'}
                onMouseLeave={e => e.currentTarget.style.opacity = '1'}
              >
                {/* Tooltip on hover */}
              </div>
            ))}
          </div>
        </div>

        {/* Active Exams */}
        <div className="clean-card" style={{ padding: 24, display: 'flex', flexDirection: 'column' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 12, color: 'var(--color-surface-400)', fontWeight: 500, marginBottom: 24 }}>
             <Clock size={20} color="var(--color-primary-400)" /> Đề thi đang mở
          </div>
          <div style={{ flex: 1, display: 'flex', flexDirection: 'column', gap: 16 }}>
            {['Toán giữa kỳ 2', 'Lý thuyết Hóa 12', 'Tiếng Anh B1'].map((t, i) => (
              <div key={i} style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
                <span style={{ fontSize: '0.9rem', fontWeight: 500, color: 'var(--color-surface-50)' }}>{t}</span>
                <span className="badge badge-gray">{Math.floor(Math.random() * 100) + 20} hs</span>
              </div>
            ))}
          </div>
        </div>
        
      </div>
    </div>
  )
}

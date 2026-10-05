import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { examApi, errorText, type Exam } from './examApi'
import { useNavigate } from 'react-router-dom'
import ExamManagement from './ExamManagement'
import { FileText, Plus, Users, Clock, Target, Trash2 } from 'lucide-react'

export default function ExamListPage() {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const [page,setPage] = useState(0)
  const [actionError,setActionError]=useState('')
  const [selected,setSelected]=useState<Exam | null>(null)
  const invites=useQuery({queryKey:['invitations'],queryFn:examApi.invitations})
  
  const { data, isLoading, error } = useQuery({
    queryKey: ['exams', page],
    queryFn: () => examApi.getExams({ page })
  })

  const deleteMut = useMutation({
    mutationFn: examApi.deleteExam,
    onSuccess: () => qc.invalidateQueries({ queryKey: ['exams'] })
  })

  const exams = data?.content ?? []

  return (
    <div style={{ paddingBottom: 40 }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 32 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <div style={{ background: 'var(--color-primary-50)', borderRadius: 8, padding: 8 }}>
            <FileText size={28} color="var(--color-primary-600)" />
          </div>
          <div>
            <h1 style={{ fontSize: '1.5rem', fontWeight: 700, color: 'var(--color-surface-50)',
              letterSpacing: '-0.02em', margin: 0 }}>Quản lý Đề thi</h1>
            <p style={{ color: 'var(--color-surface-400)', fontSize: '0.875rem', margin: 0 }}>
              {data?.totalElements ?? 0} đề thi
            </p>
          </div>
        </div>
        <button className="btn-primary" onClick={() => navigate('/exams/create')}>
           <Plus size={18} style={{ marginRight: 8 }} /> Tạo đề thi mới
        </button>
      </div>

      {(error || invites.error || deleteMut.error || actionError) && <p role="alert">{actionError || errorText(error || invites.error || deleteMut.error)}</p>}
      {invites.data?.map(e=><p key={e.id}>Lời mời: {e.title} <button onClick={()=>examApi.accept(e.id).then(()=>{qc.invalidateQueries({queryKey:['exams']});qc.invalidateQueries({queryKey:['invitations']})}).catch(e=>setActionError(errorText(e)))}>Chấp nhận</button></p>)}
      {selected && <ExamManagement exam={selected} onClose={()=>setSelected(null)} />}
      {/* Grid */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(280px, 1fr))', gap: 24 }}>
        
        {/* Create Card */}
        <div 
          onClick={() => navigate('/exams/create')}
          style={{
            border: '2px dashed var(--color-surface-700)',
            borderRadius: 'var(--radius-lg)',
            display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center',
            padding: 32, gap: 12, cursor: 'pointer', background: 'transparent',
            minHeight: 220, transition: 'all 0.2s ease'
          }}
          onMouseEnter={e => { e.currentTarget.style.borderColor = 'var(--color-primary-400)'; e.currentTarget.style.background = 'var(--color-primary-50)' }}
          onMouseLeave={e => { e.currentTarget.style.borderColor = 'var(--color-surface-700)'; e.currentTarget.style.background = 'transparent' }}
        >
          <div style={{ width: 48, height: 48, borderRadius: '50%', background: 'var(--color-primary-100)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Plus size={24} color="var(--color-primary-600)" />
          </div>
          <div style={{ textAlign: 'center' }}>
            <div style={{ fontWeight: 600, color: 'var(--color-surface-50)' }}>Tạo đề thi mới</div>
            <div style={{ fontSize: '0.8rem', color: 'var(--color-surface-400)' }}>Quy trình từng bước</div>
          </div>
        </div>

        {/* Loading Skeletons */}
        {isLoading && [...Array(3)].map((_, i) => (
          <div key={i} className="skeleton" style={{ height: 220, borderRadius: 'var(--radius-lg)' }} />
        ))}

        {/* Exam Cards */}
        {exams.map(exam => (
          <div key={exam.id} className="clean-card" style={{ padding: 20, display: 'flex', flexDirection: 'column', transition: 'transform 0.2s', cursor: 'pointer' }}
            onMouseEnter={e => e.currentTarget.style.transform = 'translateY(-4px)'}
            onMouseLeave={e => e.currentTarget.style.transform = 'translateY(0)'}>
            
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 16 }}>
              <span className={`badge ${exam.status === 'PUBLISHED' ? 'badge-green' : 'badge-gray'}`}>
                {exam.status}
              </span>
              <span className="badge badge-primary">{exam.type === 'PRACTICE' ? 'Luyện tập' : 'Thi thật'}</span>
            </div>
            
            <button className="btn-secondary" onClick={()=>setSelected(exam)}>Quản lý / Theo dõi</button>
            <h3 style={{ fontSize: '1.125rem', fontWeight: 700, margin: '0 0 8px 0', color: 'var(--color-surface-50)', lineHeight: 1.4, display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
              {exam.title}
            </h3>
            
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, color: 'var(--color-surface-400)', fontSize: '0.85rem', marginBottom: 24 }}>
               <Clock size={14} /> {exam.durationMinutes} phút
              <span style={{ margin: '0 4px' }}>•</span>
              <span style={{ fontWeight: 600 }}>{exam.code}</span>
            </div>
            
            <div style={{ marginTop: 'auto', display: 'flex', justifyContent: 'space-between', alignItems: 'center', borderTop: '1px solid var(--color-surface-700)', paddingTop: 16 }}>
              <div style={{ display: 'flex', gap: 16 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 4, color: 'var(--color-surface-400)', fontSize: '0.85rem' }}>
                   <Users size={14} /> {exam.maxParticipants} tối đa
                </div>
                <div style={{ display: 'flex', alignItems: 'center', gap: 4, color: 'var(--color-surface-400)', fontSize: '0.85rem' }}>
                   <Target size={14} /> {exam.totalScore} điểm
                </div>
              </div>
              <button 
                onClick={(e) => { e.stopPropagation(); if (confirm('Bạn muốn xoá đề thi này?')) deleteMut.mutate(exam.id) }} 
                className="btn-ghost" style={{ padding: 6, color: 'var(--color-error)' }}
              >
                <Trash2 size={16} />
              </button>
            </div>
          </div>
        ))}
      </div>
      {data && data.totalPages>1 && <nav aria-label="Trang đề thi"><button disabled={page===0} onClick={()=>setPage(p=>p-1)}>Trang trước</button><span>{page+1}/{data.totalPages}</span><button disabled={page+1>=data.totalPages} onClick={()=>setPage(p=>p+1)}>Trang sau</button></nav>}
    </div>
  )
}

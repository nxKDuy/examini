import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { questionBankApi, type Question } from '../question-bank/questionApi'
import { examApi, errorText } from './examApi'
import { X, Search, CheckCircle2 } from 'lucide-react'

interface QuestionSelectorModalProps {
  onClose: () => void;
  onSelect: (questions: Question[]) => void;
  alreadySelectedIds: string[];
}

export default function QuestionSelectorModal({ onClose, onSelect, alreadySelectedIds }: QuestionSelectorModalProps) {
  const [subjectId, setSubjectId] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [difficulty, setDifficulty] = useState('')
  const [page, setPage] = useState(0)
  
  // Selection state
  const [selected, setSelected] = useState<Map<string, Question>>(new Map())

  // Queries
  const { data: subjects } = useQuery({ queryKey: ['subjects'], queryFn: questionBankApi.getSubjects })
  const { data: categories } = useQuery({
    queryKey: ['categories', subjectId],
    queryFn: () => questionBankApi.getCategories(subjectId),
    enabled: !!subjectId,
  })
  
  const { data, isLoading, error } = useQuery({
    queryKey: ['questions', categoryId, difficulty, page],
    queryFn: () => examApi.selectQuestions({ categoryId: categoryId || undefined, difficulty: difficulty || undefined, page, size: 10 }),
  })

  const questions = data?.content ?? []
  const totalPages = data?.totalPages ?? 0

  const toggleSelect = (q: Question) => {
    if (alreadySelectedIds.includes(q.id)) return // prevent deselecting already added ones from here easily, or just ignore
    
    setSelected(prev => {
      const next = new Map(prev)
      if (next.has(q.id)) next.delete(q.id)
      else next.set(q.id, q)
      return next
    })
  }

  const handleConfirm = () => {
    onSelect(Array.from(selected.values()))
    onClose()
  }

  return (
    <div style={{
      position: 'fixed', inset: 0, zIndex: 1100,
      background: 'rgba(0,0,0,0.6)', backdropFilter: 'blur(4px)',
      display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24,
    }}>
      <div className="clean-card page-enter" style={{ 
        width: '100%', maxWidth: 800, height: '80vh', 
        display: 'flex', flexDirection: 'column', background: '#ffffff', overflow: 'hidden'
      }}>
        {/* Header */}
        <div style={{ padding: '20px 24px', borderBottom: '1px solid var(--color-surface-700)', display: 'flex', justifyContent: 'space-between', alignItems: 'center', background: 'var(--color-surface-950)' }}>
          <div>
            <h2 style={{ fontSize: '1.25rem', fontWeight: 700, color: 'var(--color-surface-50)', margin: 0 }}>Chọn câu hỏi từ Ngân hàng</h2>
            <div style={{ fontSize: '0.85rem', color: 'var(--color-surface-400)', marginTop: 4 }}>
              Đã chọn: <strong style={{ color: 'var(--color-primary-600)' }}>{selected.size}</strong> câu hỏi mới
            </div>
          </div>
          <button onClick={onClose} className="btn-ghost"><X size={20} /></button>
        </div>

        {/* Filters */}
        <div style={{ padding: '16px 24px', borderBottom: '1px solid var(--color-surface-700)', background: 'var(--color-surface-900)', display: 'flex', gap: 12 }}>
          <select value={subjectId} onChange={e => { setSubjectId(e.target.value); setCategoryId('') }} className="input-field" style={{ flex: 1 }}>
            <option value="">Tất cả môn học</option>
            {subjects?.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
          </select>
          <select value={categoryId} onChange={e => setCategoryId(e.target.value)} disabled={!subjectId} className="input-field" style={{ flex: 1, opacity: !subjectId ? 0.5 : 1 }}>
            <option value="">Tất cả danh mục</option>
            {categories?.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
          <select value={difficulty} onChange={e => setDifficulty(e.target.value)} className="input-field" style={{ flex: 1 }}>
            <option value="">Tất cả độ khó</option>
            <option value="EASY">Dễ</option>
            <option value="MEDIUM">Trung bình</option>
            <option value="HARD">Khó</option>
          </select>
        </div>

        {/* List */}
        <div style={{ flex: 1, overflowY: 'auto', padding: 24, display: 'flex', flexDirection: 'column', gap: 12 }}>
          {error ? <p role="alert">{errorText(error)}</p> : isLoading ? (
            <div style={{ textAlign: 'center', padding: 40, color: 'var(--color-surface-400)' }}>Đang tải...</div>
          ) : questions.length === 0 ? (
            <div style={{ textAlign: 'center', padding: 40 }}>
              <Search size={40} color="var(--color-surface-600)" style={{ marginBottom: 16 }} />
              <div style={{ color: 'var(--color-surface-400)' }}>Không tìm thấy câu hỏi phù hợp.</div>
            </div>
          ) : (
            questions.map(q => {
              const isAlreadyAdded = alreadySelectedIds.includes(q.id)
              const isSelected = selected.has(q.id)
              
              return (
                <div key={q.id} 
                  onClick={() => !isAlreadyAdded && toggleSelect(q)}
                  style={{
                    padding: 16, borderRadius: 8, cursor: isAlreadyAdded ? 'not-allowed' : 'pointer',
                    border: `1px solid ${isSelected ? 'var(--color-primary-400)' : 'var(--color-surface-700)'}`,
                    background: isSelected ? 'var(--color-primary-50)' : isAlreadyAdded ? 'var(--color-surface-800)' : '#ffffff',
                    opacity: isAlreadyAdded ? 0.6 : 1,
                    display: 'flex', gap: 16, alignItems: 'flex-start',
                    transition: 'all 0.15s'
                  }}>
                  
                  <div style={{
                    width: 20, height: 20, borderRadius: 4, flexShrink: 0, marginTop: 2,
                    border: `2px solid ${isSelected || isAlreadyAdded ? 'var(--color-primary-500)' : 'var(--color-surface-600)'}`,
                    background: isSelected || isAlreadyAdded ? 'var(--color-primary-500)' : 'transparent',
                    display: 'flex', alignItems: 'center', justifyContent: 'center'
                  }}>
                    {(isSelected || isAlreadyAdded) && <CheckCircle2 size={14} color="#fff" />}
                  </div>
                  
                  <div style={{ flex: 1 }}>
                    <div style={{ display: 'flex', gap: 8, marginBottom: 8 }}>
                      <span className="badge badge-gray" style={{ fontSize: '0.7rem' }}>{q.questionType}</span>
                      <span className="badge badge-gray" style={{ fontSize: '0.7rem' }}>{q.difficultyLevel}</span>
                      {isAlreadyAdded && <span className="badge badge-yellow" style={{ fontSize: '0.7rem' }}>Đã có trong đề</span>}
                    </div>
                    <div style={{ fontSize: '0.95rem', color: 'var(--color-surface-50)', lineHeight: 1.5, display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
                      {q.content}
                    </div>
                  </div>
                </div>
              )
            })
          )}
        </div>

        {/* Pagination & Footer */}
        <div style={{ padding: '16px 24px', borderTop: '1px solid var(--color-surface-700)', background: 'var(--color-surface-900)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div style={{ display: 'flex', gap: 16, alignItems: 'center' }}>
            <button className="btn-secondary" onClick={() => setPage(p => p - 1)} disabled={page === 0}>Trước</button>
            <span style={{ fontSize: '0.875rem', color: 'var(--color-surface-400)' }}>Trang {page + 1} / {totalPages || 1}</span>
            <button className="btn-secondary" onClick={() => setPage(p => p + 1)} disabled={page >= totalPages - 1}>Sau</button>
          </div>
          
          <div style={{ display: 'flex', gap: 12 }}>
            <button className="btn-secondary" onClick={onClose}>Hủy</button>
            <button className="btn-primary" onClick={handleConfirm} disabled={selected.size === 0}>
              Thêm {selected.size > 0 ? selected.size : ''} câu hỏi
            </button>
          </div>
        </div>

      </div>
    </div>
  )
}

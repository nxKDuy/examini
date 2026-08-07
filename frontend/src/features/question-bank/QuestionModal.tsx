import { useState, useEffect } from 'react'
import { X, Plus, Trash2, CheckCircle2 } from 'lucide-react'
import { questionBankApi, type Category, type Question } from './questionApi'
import { useMutation, useQueryClient } from '@tanstack/react-query'

interface OptionState {
  id: string; // temp id for UI
  content: string;
  correct: boolean;
}

interface QuestionModalProps {
  categories: Category[];
  initialData?: Question | null;
  onClose: () => void;
}

export default function QuestionModal({ categories, initialData, onClose }: QuestionModalProps) {
  const qc = useQueryClient()
  
  const [categoryId, setCategoryId] = useState(initialData?.categoryId || '')
  const [content, setContent] = useState(initialData?.content || '')
  const [type, setType] = useState(initialData?.questionType || 'MULTIPLE_CHOICE')
  const [difficulty, setDifficulty] = useState(initialData?.difficultyLevel || 'MEDIUM')
  const [options, setOptions] = useState<OptionState[]>(() => {
    if (initialData?.options && initialData.options.length > 0) {
      return initialData.options.map((o: any) => ({ ...o, id: o.id || Math.random().toString() }))
    }
    return [
      { id: '1', content: '', correct: true },
      { id: '2', content: '', correct: false },
      { id: '3', content: '', correct: false },
      { id: '4', content: '', correct: false },
    ]
  })

  const isEdit = !!initialData

  const saveMut = useMutation({
    mutationFn: (data: any) => isEdit 
      ? questionBankApi.updateQuestion(initialData.id, data)
      : questionBankApi.createQuestion(data),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['questions'] })
      onClose()
    },
    onError: () => alert(`Lỗi khi ${isEdit ? 'cập nhật' : 'tạo'} câu hỏi`),
  })

  const handleAddOption = () => {
    setOptions(prev => [...prev, { id: Math.random().toString(), content: '', correct: false }])
  }

  const handleRemoveOption = (id: string) => {
    setOptions(prev => prev.filter(o => o.id !== id))
  }

  const handleOptionChange = (id: string, text: string) => {
    setOptions(prev => prev.map(o => o.id === id ? { ...o, content: text } : o))
  }

  const handleSetCorrect = (id: string) => {
    if (type === 'MULTIPLE_CHOICE' || type === 'TRUE_FALSE') {
      // Single correct answer for these types in simple model
      setOptions(prev => prev.map(o => ({ ...o, correct: o.id === id })))
    }
  }

  useEffect(() => {
    if (isEdit) return // Do not auto-reset options when in edit mode

    if (type === 'TRUE_FALSE') {
      setOptions([
        { id: '1', content: 'Đúng', correct: true },
        { id: '2', content: 'Sai', correct: false },
      ])
    } else if (type === 'ESSAY' || type === 'SHORT_ANSWER') {
      setOptions([])
    } else if (type === 'MULTIPLE_CHOICE' && options.length < 2) {
      setOptions([
        { id: '1', content: '', correct: true },
        { id: '2', content: '', correct: false },
        { id: '3', content: '', correct: false },
        { id: '4', content: '', correct: false },
      ])
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [type, isEdit])

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault()
    if (!categoryId || !content.trim()) return alert('Vui lòng nhập đủ thông tin')
    
    let submitOptions: any[] = []
    if (type === 'MULTIPLE_CHOICE' || type === 'TRUE_FALSE') {
      const validOptions = options.filter(o => o.content.trim() !== '')
      if (validOptions.length < 2) return alert('Câu hỏi trắc nghiệm cần ít nhất 2 lựa chọn')
      if (!validOptions.some(o => o.correct)) return alert('Vui lòng chọn 1 đáp án đúng')
      
      submitOptions = validOptions.map((o, idx) => ({
        content: o.content.trim(),
        correct: o.correct,
        displayOrder: idx
      }))
    }

    saveMut.mutate({
      categoryId,
      content: content.trim(),
      questionType: type,
      difficultyLevel: difficulty,
      options: submitOptions
    })
  }

  return (
    <div style={{
      position: 'fixed', inset: 0, zIndex: 1000,
      background: 'rgba(0,0,0,0.5)', backdropFilter: 'blur(4px)',
      display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 24,
    }}>
      <div className="clean-card page-enter" style={{ 
        width: '100%', maxWidth: 720, maxHeight: '90vh', 
        display: 'flex', flexDirection: 'column',
        background: '#ffffff', overflow: 'hidden'
      }}>
        {/* Header */}
        <div style={{ padding: '20px 24px', borderBottom: '1px solid var(--color-surface-700)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <h2 style={{ fontSize: '1.25rem', fontWeight: 600, color: 'var(--color-surface-50)', margin: 0 }}>
            {isEdit ? 'Sửa câu hỏi' : 'Thêm câu hỏi mới'}
          </h2>
          <button onClick={onClose} className="btn-ghost" style={{ padding: 4 }}><X size={20} /></button>
        </div>

        {/* Form Body */}
        <div style={{ flex: 1, overflowY: 'auto', padding: 24 }}>
          <form id="question-form" onSubmit={handleSubmit} style={{ display: 'flex', flexDirection: 'column', gap: 20 }}>
            
            <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr 1fr', gap: 16 }}>
              <div>
                <label style={{ display: 'block', fontSize: '0.875rem', fontWeight: 600, marginBottom: 8, color: 'var(--color-surface-200)' }}>Danh mục *</label>
                <select value={categoryId} onChange={e => setCategoryId(e.target.value)} className="input-field" required>
                  <option value="">Chọn danh mục</option>
                  {categories.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
                </select>
              </div>
              <div>
                <label style={{ display: 'block', fontSize: '0.875rem', fontWeight: 600, marginBottom: 8, color: 'var(--color-surface-200)' }}>Loại câu hỏi</label>
                <select value={type} onChange={e => setType(e.target.value)} className="input-field" disabled={isEdit}>
                  <option value="MULTIPLE_CHOICE">Trắc nghiệm</option>
                  <option value="TRUE_FALSE">Đúng / Sai</option>
                  <option value="SHORT_ANSWER">Trả lời ngắn</option>
                  <option value="ESSAY">Tự luận</option>
                </select>
              </div>
              <div>
                <label style={{ display: 'block', fontSize: '0.875rem', fontWeight: 600, marginBottom: 8, color: 'var(--color-surface-200)' }}>Độ khó</label>
                <select value={difficulty} onChange={e => setDifficulty(e.target.value)} className="input-field">
                  <option value="EASY">Dễ</option>
                  <option value="MEDIUM">Trung bình</option>
                  <option value="HARD">Khó</option>
                </select>
              </div>
            </div>

            <div>
              <label style={{ display: 'block', fontSize: '0.875rem', fontWeight: 600, marginBottom: 8, color: 'var(--color-surface-200)' }}>Nội dung câu hỏi *</label>
              <textarea 
                value={content} onChange={e => setContent(e.target.value)} 
                className="input-field" rows={4} required
                placeholder="Nhập nội dung câu hỏi..."
                style={{ resize: 'vertical' }}
              />
            </div>

            {(type === 'MULTIPLE_CHOICE' || type === 'TRUE_FALSE') && (
              <div>
                <label style={{ display: 'block', fontSize: '0.875rem', fontWeight: 600, marginBottom: 12, color: 'var(--color-surface-200)' }}>
                  Các lựa chọn đáp án
                </label>
                
                <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                  {options.map((opt, idx) => (
                    <div key={opt.id} style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
                      <button type="button" onClick={() => handleSetCorrect(opt.id)}
                        style={{ 
                          width: 24, height: 24, borderRadius: '50%', flexShrink: 0,
                          background: opt.correct ? 'var(--color-success)' : 'transparent',
                          border: `2px solid ${opt.correct ? 'var(--color-success)' : 'var(--color-surface-700)'}`,
                          color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center',
                          cursor: 'pointer'
                        }}>
                        {opt.correct && <CheckCircle2 size={14} />}
                      </button>
                      <div style={{ fontWeight: 600, width: 24, color: 'var(--color-surface-400)' }}>{String.fromCharCode(65 + idx)}</div>
                      <input 
                        type="text" value={opt.content} onChange={e => handleOptionChange(opt.id, e.target.value)}
                        className="input-field" placeholder="Nhập đáp án..." style={{ flex: 1 }}
                        disabled={type === 'TRUE_FALSE'}
                      />
                      {type === 'MULTIPLE_CHOICE' && options.length > 2 && (
                        <button type="button" onClick={() => handleRemoveOption(opt.id)} className="btn-ghost" style={{ color: 'var(--color-error)' }}>
                          <Trash2 size={18} />
                        </button>
                      )}
                    </div>
                  ))}
                </div>

                {type === 'MULTIPLE_CHOICE' && (
                  <button type="button" onClick={handleAddOption} className="btn-secondary" style={{ marginTop: 12 }}>
                    <Plus size={16} style={{ marginRight: 6 }} /> Thêm lựa chọn
                  </button>
                )}
              </div>
            )}
          </form>
        </div>

        {/* Footer */}
        <div style={{ padding: '16px 24px', borderTop: '1px solid var(--color-surface-700)', display: 'flex', justifyContent: 'flex-end', gap: 12, background: 'var(--color-surface-900)' }}>
          <button type="button" onClick={onClose} className="btn-secondary" disabled={saveMut.isPending}>Hủy</button>
          <button type="submit" form="question-form" className="btn-primary" disabled={saveMut.isPending}>
            {saveMut.isPending ? 'Đang lưu...' : (isEdit ? 'Lưu thay đổi' : 'Tạo câu hỏi')}
          </button>
        </div>
      </div>
    </div>
  )
}

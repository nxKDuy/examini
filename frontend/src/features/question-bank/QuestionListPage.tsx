import { useState, useRef } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { questionBankApi, type Question } from './questionApi'
import { BookOpen, Search, ChevronLeft, ChevronRight, Trash2, Plus, Upload, Book, CheckCircle2 } from 'lucide-react'
import QuestionModal from './QuestionModal'

const DIFFICULTY_LABEL: Record<string, { label: string; cls: string }> = {
  EASY:   { label: 'Dễ',    cls: 'badge-green' },
  MEDIUM: { label: 'Trung bình', cls: 'badge-yellow' },
  HARD:   { label: 'Khó',   cls: 'badge-primary' },
}
const TYPE_LABEL: Record<string, string> = {
  MULTIPLE_CHOICE: 'Trắc nghiệm',
  TRUE_FALSE:      'Đúng/Sai',
  SHORT_ANSWER:    'Trả lời ngắn',
  ESSAY:           'Tự luận',
}

export default function QuestionListPage() {
  const qc = useQueryClient()
  const fileRef = useRef<HTMLInputElement>(null)
  const [categoryId, setCategoryId] = useState('')
  const [subjectId, setSubjectId] = useState('')
  const [difficulty, setDifficulty] = useState('')
  const [page, setPage] = useState(0)
  const [importCategoryId, setImportCategoryId] = useState('')
  const [selectedQ, setSelectedQ] = useState<Question | null>(null)
  const [showModal, setShowModal] = useState(false)
  const [editingQ, setEditingQ] = useState<Question | null>(null)

  const { data: subjects } = useQuery({ queryKey: ['subjects'], queryFn: questionBankApi.getSubjects })
  const { data: categories } = useQuery({
    queryKey: ['categories', subjectId],
    queryFn: () => questionBankApi.getCategories(subjectId),
    enabled: !!subjectId,
  })
  const { data, isLoading } = useQuery({
    queryKey: ['questions', categoryId, difficulty, page],
    queryFn: () => questionBankApi.getQuestions({ categoryId: categoryId || undefined, difficulty: difficulty || undefined, page }),
  })

  const deleteMut = useMutation({
    mutationFn: questionBankApi.deleteQuestion,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['questions'] })
      setSelectedQ(null)
    },
  })

  const importMut = useMutation({
    mutationFn: ({ file, catId }: { file: File; catId: string }) => questionBankApi.importCsv(file, catId),
    onSuccess: (result) => {
      qc.invalidateQueries({ queryKey: ['questions'] })
      alert(`✅ Import xong: ${result.imported} thành công, ${result.failed} lỗi`)
    },
    onError: () => alert('❌ Lỗi khi import file CSV'),
  })

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0]
    if (!file) return
    if (!importCategoryId) { alert('Vui lòng chọn danh mục trước khi import'); return }
    importMut.mutate({ file, catId: importCategoryId })
    e.target.value = ''
  }

  const questions = data?.content ?? []
  const totalPages = data?.totalPages ?? 0

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 128px)' }}>
      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 24 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <div style={{ background: 'var(--color-primary-50)', borderRadius: 8, padding: 8 }}>
            <BookOpen size={28} color="var(--color-primary-600)" />
          </div>
          <div>
            <h1 style={{ fontSize: '1.5rem', fontWeight: 700, color: 'var(--color-surface-50)',
              letterSpacing: '-0.02em', margin: 0 }}>Ngân hàng câu hỏi</h1>
            <p style={{ color: 'var(--color-surface-400)', fontSize: '0.875rem', margin: 0 }}>
              {data?.totalElements ?? 0} câu hỏi trong hệ thống
            </p>
          </div>
        </div>

        <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
          <select value={importCategoryId} onChange={e => setImportCategoryId(e.target.value)}
            className="input-field" style={{ width: 200, height: 36 }}>
            <option value="">Chọn danh mục import</option>
            {categories?.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
          </select>
          <input ref={fileRef} type="file" accept=".csv" style={{ display: 'none' }}
            onChange={handleFileChange} />
          <button className="btn-secondary" onClick={() => fileRef.current?.click()} disabled={importMut.isPending}>
             {importMut.isPending ? 'Đang import...' : <><Upload size={16} style={{ marginRight: 8 }} /> Import CSV</>}
          </button>
          <button className="btn-primary" onClick={() => setShowModal(true)}>
             <Plus size={16} style={{ marginRight: 8 }} /> Thêm câu hỏi
          </button>
        </div>
      </div>

      {/* Split View Container */}
      <div style={{ display: 'flex', gap: 24, flex: 1, minHeight: 0 }}>
        
        {/* Left: Master List */}
        <div className="clean-card" style={{ flex: '1 1 40%', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
          
          {/* Filters inside Master List */}
          <div style={{ padding: '16px', borderBottom: '1px solid var(--color-surface-700)', background: 'var(--color-surface-900)' }}>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              <select value={subjectId} onChange={e => { setSubjectId(e.target.value); setCategoryId('') }}
                className="input-field" style={{ flex: 1, minWidth: 120 }}>
                <option value="">Tất cả môn học</option>
                {subjects?.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
              </select>
              <select value={categoryId} onChange={e => setCategoryId(e.target.value)} disabled={!subjectId}
                className="input-field" style={{ flex: 1, minWidth: 120, opacity: !subjectId ? 0.5 : 1 }}>
                <option value="">Tất cả danh mục</option>
                {categories?.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
              </select>
              <select value={difficulty} onChange={e => setDifficulty(e.target.value)}
                className="input-field" style={{ flex: 1, minWidth: 100 }}>
                <option value="">Tất cả độ khó</option>
                <option value="EASY">Dễ</option>
                <option value="MEDIUM">TB</option>
                <option value="HARD">Khó</option>
              </select>
            </div>
          </div>

          {/* List */}
          <div style={{ flex: 1, overflowY: 'auto', padding: 12, display: 'flex', flexDirection: 'column', gap: 8 }}>
            {isLoading ? (
              [...Array(6)].map((_, i) => <div key={i} className="skeleton" style={{ height: 80, borderRadius: 8 }} />)
            ) : questions.length === 0 ? (
              <div style={{ padding: '40px 20px', textAlign: 'center' }}>
                <Search size={48} color="var(--color-surface-700)" style={{ marginBottom: 16 }} />
                <p style={{ color: 'var(--color-surface-400)', fontSize: '0.9rem' }}>Không tìm thấy câu hỏi</p>
              </div>
            ) : (
              questions.map(q => (
                <div key={q.id} 
                  onClick={() => setSelectedQ(q)}
                  style={{ 
                    padding: 16, 
                    borderRadius: 8, 
                    border: `1px solid ${selectedQ?.id === q.id ? 'var(--color-primary-400)' : 'var(--color-surface-700)'}`,
                    background: selectedQ?.id === q.id ? 'var(--color-primary-50)' : 'var(--color-surface-950)',
                    cursor: 'pointer',
                    transition: 'all 0.15s ease'
                  }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
                    <span className="badge badge-gray">{TYPE_LABEL[q.questionType]}</span>
                    <span className={`badge ${DIFFICULTY_LABEL[q.difficultyLevel].cls}`}>
                      {DIFFICULTY_LABEL[q.difficultyLevel].label}
                    </span>
                  </div>
                  <div style={{ fontSize: '0.95rem', color: 'var(--color-surface-50)', fontWeight: 500, 
                    display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
                    {q.content}
                  </div>
                </div>
              ))
            )}
          </div>

          {/* Pagination */}
          {totalPages > 1 && (
            <div style={{ padding: '12px 16px', borderTop: '1px solid var(--color-surface-700)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontSize: '0.8rem', color: 'var(--color-surface-400)' }}>Trang {page + 1} / {totalPages}</span>
              <div style={{ display: 'flex', gap: 4 }}>
                <button className="btn-ghost" onClick={() => setPage(p => p - 1)} disabled={page === 0}><ChevronLeft size={20} /></button>
                <button className="btn-ghost" onClick={() => setPage(p => p + 1)} disabled={page >= totalPages - 1}><ChevronRight size={20} /></button>
              </div>
            </div>
          )}
        </div>

        {/* Right: Detail View */}
        <div className="clean-card" style={{ flex: '1 1 60%', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
          {selectedQ ? (
            <div style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
              <div style={{ padding: '20px 24px', borderBottom: '1px solid var(--color-surface-700)', display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                <div>
                  <h2 style={{ fontSize: '1.25rem', margin: '0 0 8px 0', color: 'var(--color-surface-50)' }}>Chi tiết câu hỏi</h2>
                  <div style={{ display: 'flex', gap: 12, fontSize: '0.85rem', color: 'var(--color-surface-400)' }}>
                    <span>Danh mục: <strong>{selectedQ.categoryName}</strong></span>
                    <span>•</span>
                    <span>Tạo lúc: {new Date(selectedQ.createdAt).toLocaleDateString('vi-VN')}</span>
                  </div>
                </div>
                <div style={{ display: 'flex', gap: 8 }}>
                  <button className="btn-secondary" onClick={() => setEditingQ(selectedQ)}>Sửa</button>
                  <button className="btn-secondary" style={{ color: 'var(--color-error)' }} 
                    onClick={() => { if(confirm('Xóa câu hỏi này?')) deleteMut.mutate(selectedQ.id) }}>
                    <Trash2 size={16} />
                  </button>
                </div>
              </div>
              
              <div style={{ flex: 1, overflowY: 'auto', padding: '24px' }}>
                <div style={{ fontSize: '1.1rem', color: 'var(--color-surface-50)', lineHeight: 1.6, marginBottom: 32, whiteSpace: 'pre-wrap' }}>
                  {selectedQ.content}
                </div>
                
                {selectedQ.options && selectedQ.options.length > 0 && (
                  <div>
                    <h3 style={{ fontSize: '0.9rem', color: 'var(--color-surface-400)', textTransform: 'uppercase', letterSpacing: '0.05em', marginBottom: 16 }}>
                      Các lựa chọn đáp án
                    </h3>
                    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                      {selectedQ.options.map((opt, idx) => (
                        <div key={opt.id} style={{ 
                          display: 'flex', gap: 12, alignItems: 'flex-start',
                          padding: 16, borderRadius: 8,
                          background: opt.correct ? 'var(--color-primary-50)' : 'var(--color-surface-900)',
                          border: `1px solid ${opt.correct ? 'var(--color-primary-300)' : 'var(--color-surface-700)'}`
                        }}>
                          <div style={{ width: 24, height: 24, borderRadius: '50%', background: opt.correct ? 'var(--color-primary-500)' : 'var(--color-surface-700)', color: 'white', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '0.8rem', fontWeight: 600, flexShrink: 0 }}>
                            {String.fromCharCode(65 + idx)}
                          </div>
                          <div style={{ flex: 1, fontSize: '0.95rem', color: 'var(--color-surface-50)' }}>
                            {opt.content}
                          </div>
                          {opt.correct && <span style={{ color: 'var(--color-success)' }}><CheckCircle2 size={18} /></span>}
                        </div>
                      ))}
                    </div>
                  </div>
                )}
              </div>
            </div>
          ) : (
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: '100%', color: 'var(--color-surface-400)' }}>
              <Book size={48} color="var(--color-surface-700)" style={{ marginBottom: 16 }} />
              <p>Chọn một câu hỏi ở danh sách bên trái để xem chi tiết.</p>
            </div>
          )}
        </div>

      </div>

      {/* Modal */}
      {(showModal || editingQ) && (
        <QuestionModal 
          categories={categories ?? []} 
          initialData={editingQ}
          onClose={() => { setShowModal(false); setEditingQ(null); }} 
        />
      )}
    </div>
  )
}

import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { gradingApi, type Submission } from './gradingApi'
import { errorText, examApi } from '../exams/examApi'
import {
  CheckSquare, Search, User, Save, FileText,
  ChevronLeft, ChevronRight, CheckCircle2, XCircle, Clock, Filter
} from 'lucide-react'

const STATUS_LABEL: Record<string, { label: string; cls: string }> = {
  SUBMITTED:    { label: 'Chờ chấm',   cls: 'badge-yellow' },
  PENDING:      { label: 'Chờ chấm',   cls: 'badge-yellow' },
  GRADED:       { label: 'Đã chấm',    cls: 'badge-green' },
  NEEDS_REVIEW: { label: 'Cần xét duyệt', cls: 'badge-yellow' },
}

export default function GradingPage() {
  const qc = useQueryClient()

  // Filters
  const [examId, setExamId] = useState('')
  const [statusFilter, setStatusFilter] = useState('')
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(0)

  // Selected
  const [selectedId, setSelectedId] = useState<string | null>(null)

  // Local grading state
  const [inputScore, setInputScore] = useState<string>('')
  const [inputFeedback, setInputFeedback] = useState('')

  // Queries
  const { data: exams } = useQuery({
    queryKey: ['exams', 'all'],
    queryFn: () => examApi.getExams({ size: 100 })
  })

  const { data: submissionsPage, isLoading: listLoading, error: listError } = useQuery({
    queryKey: ['submissions', examId, statusFilter, page],
    enabled: !!examId,
    queryFn: () => gradingApi.getSubmissions({ examId: examId || undefined, status: statusFilter || undefined, page }),
  })

  const { data: detail, isLoading: detailLoading, error: detailError } = useQuery({
    queryKey: ['submission-detail', selectedId],
    queryFn: () => gradingApi.getSubmissionDetail(selectedId!),
    enabled: !!selectedId,
  })

  const gradeMut = useMutation({
    mutationFn: ({ id, score, feedback }: { id: string; score: number; feedback: string }) =>
      gradingApi.gradeSubmission(id, { score, feedback }),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['submissions'] })
      qc.invalidateQueries({ queryKey: ['submission-detail', selectedId] })
    },
  })

  const submissions = submissionsPage?.content ?? []
  const totalPages = submissionsPage?.totalPages ?? 0

  const handleSelectSubmission = (s: Submission) => {
    setSelectedId(s.id)
    setInputScore(s.score !== null ? String(s.score) : '')
    setInputFeedback(s.feedback ?? '')
  }

  const handleGrade = () => {
    if (!selectedId) return
    const score = parseFloat(inputScore)
    if (isNaN(score)) return alert('Nhập điểm hợp lệ')
    if (detail && (score < 0 || score > detail.totalScore)) return alert(`Điểm phải từ 0 đến ${detail?.totalScore}`)
    gradeMut.mutate({ id: selectedId, score, feedback: inputFeedback })
  }

  const filteredSubmissions = submissions.filter(s =>
    !search || s.studentName.toLowerCase().includes(search.toLowerCase()) || s.studentCode.toLowerCase().includes(search.toLowerCase())
  )

  const scorePercent = detail && detail.score !== null ? Math.round((detail.score / detail.totalScore) * 100) : null
  const scoreColor = scorePercent === null ? 'var(--color-surface-400)' : scorePercent >= 50 ? 'var(--color-success)' : 'var(--color-error)'

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: 'calc(100vh - 128px)' }}>

      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 24 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12 }}>
          <div style={{ background: 'var(--color-primary-50)', borderRadius: 8, padding: 8 }}>
            <CheckSquare size={28} color="var(--color-primary-600)" />
          </div>
          <div>
            <h1 style={{ fontSize: '1.5rem', fontWeight: 700, color: 'var(--color-surface-50)', letterSpacing: '-0.02em', margin: 0 }}>Chấm bài</h1>
            <p style={{ color: 'var(--color-surface-400)', fontSize: '0.875rem', margin: 0 }}>
              {submissionsPage?.totalElements ?? 0} bài nộp
            </p>
          </div>
        </div>

        {/* Filters */}
        <div style={{ display: 'flex', gap: 12, alignItems: 'center' }}>
          <select value={examId} onChange={e => { setExamId(e.target.value); setPage(0); setSelectedId(null) }}
            className="input-field" style={{ width: 220 }}>
            <option value="">Tất cả đề thi</option>
            {exams?.content.map(e => <option key={e.id} value={e.id}>{e.title}</option>)}
          </select>

          <select value={statusFilter} onChange={e => { setStatusFilter(e.target.value); setPage(0) }}
            className="input-field" style={{ width: 140 }}>
            <option value="">Tất cả trạng thái</option>
            <option value="PENDING">Chờ chấm</option>
            <option value="GRADED">Đã chấm</option>
            <option value="NEEDS_REVIEW">Cần xét duyệt</option>
          </select>
        </div>
      </div>

      {gradeMut.error && <p role="alert">Lưu điểm thất bại. Vui lòng thử lại.</p>}
      {/* Split View */}
      <div style={{ display: 'flex', gap: 24, flex: 1, minHeight: 0 }}>

        {/* Left: Student List */}
        <div className="clean-card" style={{ flex: '0 0 300px', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>

          {/* Search */}
          <div style={{ padding: '16px', borderBottom: '1px solid var(--color-surface-700)', background: 'var(--color-surface-900)' }}>
            <div style={{ position: 'relative' }}>
              <Search size={15} color="var(--color-surface-400)" style={{ position: 'absolute', left: 12, top: '50%', transform: 'translateY(-50%)' }} />
              <input type="text" value={search} onChange={e => setSearch(e.target.value)}
                placeholder="Tìm tên / MSSV..." className="input-field" style={{ paddingLeft: 36, height: 38 }} />
            </div>
          </div>

          {/* List */}
          <div style={{ flex: 1, overflowY: 'auto', padding: '8px 12px', display: 'flex', flexDirection: 'column', gap: 8 }}>
            {listError ? <p role="alert">{errorText(listError)}</p> : listLoading ? (
              [...Array(5)].map((_, i) => <div key={i} className="skeleton" style={{ height: 68, borderRadius: 8 }} />)
            ) : filteredSubmissions.length === 0 ? (
              <div style={{ padding: 32, textAlign: 'center', color: 'var(--color-surface-400)' }}>
                <Filter size={32} style={{ marginBottom: 8, opacity: 0.4 }} />
                <div>Không có bài nộp nào</div>
              </div>
            ) : filteredSubmissions.map(s => (
              <div key={s.id} onClick={() => handleSelectSubmission(s)}
                style={{
                  padding: '12px 14px', borderRadius: 8, cursor: 'pointer', transition: 'all 0.15s',
                  border: `1px solid ${selectedId === s.id ? 'var(--color-primary-400)' : 'var(--color-surface-700)'}`,
                  background: selectedId === s.id ? 'var(--color-primary-50)' : '#fff',
                  display: 'flex', flexDirection: 'column', gap: 6
                }}>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <div>
                    <div style={{ fontWeight: 600, fontSize: '0.9rem', color: 'var(--color-surface-50)' }}>{s.studentName}</div>
                    <div style={{ fontSize: '0.75rem', color: 'var(--color-surface-400)' }}>{s.studentCode}</div>
                  </div>
                  <span className={`badge ${STATUS_LABEL[s.status].cls}`}>{STATUS_LABEL[s.status].label}</span>
                </div>
                <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 4, fontSize: '0.75rem', color: 'var(--color-surface-400)' }}>
                    <Clock size={12} /> {new Date(s.submittedAt).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' })} - {new Date(s.submittedAt).toLocaleDateString('vi-VN')}
                  </div>
                  {s.score !== null && (
                    <div style={{ fontSize: '0.85rem', fontWeight: 700, color: scoreColor }}>{s.score}/{s.totalScore}</div>
                  )}
                </div>
              </div>
            ))}
          </div>

          {/* Pagination */}
          {totalPages > 1 && (
            <div style={{ padding: '10px 16px', borderTop: '1px solid var(--color-surface-700)', display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <span style={{ fontSize: '0.75rem', color: 'var(--color-surface-400)' }}>Trang {page + 1}/{totalPages}</span>
              <div style={{ display: 'flex', gap: 4 }}>
                <button className="btn-ghost" onClick={() => setPage(p => p - 1)} disabled={page === 0}><ChevronLeft size={18} /></button>
                <button className="btn-ghost" onClick={() => setPage(p => p + 1)} disabled={page >= totalPages - 1}><ChevronRight size={18} /></button>
              </div>
            </div>
          )}
        </div>

        {/* Right: Detail */}
        <div className="clean-card" style={{ flex: 1, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
          {!selectedId ? (
            <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', height: '100%', color: 'var(--color-surface-400)' }}>
              <FileText size={48} color="var(--color-surface-700)" style={{ marginBottom: 16 }} />
              <p>Chọn một bài nộp ở danh sách bên trái để chấm điểm.</p>
            </div>
          ) : detailError ? <p role="alert">{errorText(detailError)}</p> : detailLoading ? (
            <div style={{ padding: 40, display: 'flex', flexDirection: 'column', gap: 16 }}>
              {[...Array(4)].map((_, i) => <div key={i} className="skeleton" style={{ height: 80, borderRadius: 8 }} />)}
            </div>
          ) : detail ? (
            <>
              {/* Detail Header */}
              <div style={{ padding: '20px 24px', borderBottom: '1px solid var(--color-surface-700)', display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexShrink: 0 }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
                  <div style={{ width: 44, height: 44, borderRadius: '50%', background: 'var(--color-primary-100)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
                    <User size={22} color="var(--color-primary-600)" />
                  </div>
                  <div>
                    <div style={{ fontSize: '1.1rem', fontWeight: 700, color: 'var(--color-surface-50)' }}>{detail.studentName}</div>
                    <div style={{ fontSize: '0.8rem', color: 'var(--color-surface-400)' }}>{detail.studentCode} • Nộp: {new Date(detail.submittedAt).toLocaleString('vi-VN')}</div>
                  </div>
                </div>

                {/* Score Input */}
                <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
                  {scorePercent !== null && (
                    <div style={{ fontSize: '1.75rem', fontWeight: 800, color: scoreColor, lineHeight: 1 }}>
                      {detail.score}<span style={{ fontSize: '1rem', fontWeight: 400, color: 'var(--color-surface-400)' }}>/{detail.totalScore}</span>
                    </div>
                  )}
                  <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
                    <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
                      <span style={{ fontSize: '0.85rem', fontWeight: 600, color: 'var(--color-surface-200)', whiteSpace: 'nowrap' }}>Điểm thủ công:</span>
                      <input type="number" className="input-field" value={inputScore}
                        onChange={e => setInputScore(e.target.value)}
                        placeholder={`0–${detail.totalScore}`}
                        style={{ width: 80, textAlign: 'center', fontWeight: 700 }}
                        min={0} max={detail.totalScore} step={0.5} />
                    </div>
                    <button className="btn-primary" onClick={handleGrade} disabled={gradeMut.isPending} style={{ padding: '8px 20px' }}>
                      <Save size={15} style={{ marginRight: 6 }} /> {gradeMut.isPending ? 'Đang lưu...' : 'Lưu điểm'}
                    </button>
                  </div>
                </div>
              </div>

              {/* Answer List */}
              <div style={{ flex: 1, overflowY: 'auto', padding: 24, display: 'flex', flexDirection: 'column', gap: 20 }}>

                {detail.answers.map((ans, idx) => (
                  <div key={ans.questionId} style={{ padding: 24, border: '1px solid var(--color-surface-700)', borderRadius: 12, background: '#fff', display: 'flex', flexDirection: 'column', gap: 16 }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                      <div style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
                        <div style={{ width: 28, height: 28, borderRadius: '50%', background: 'var(--color-surface-800)', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: '0.8rem', fontWeight: 700, color: '#fff', flexShrink: 0 }}>
                          {idx + 1}
                        </div>
                        <div style={{ fontSize: '1rem', color: 'var(--color-surface-50)', lineHeight: 1.6, fontWeight: 500 }}>
                          {ans.questionContent}
                        </div>
                      </div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexShrink: 0, marginLeft: 16 }}>
                        {ans.isCorrect !== undefined && (
                          ans.isCorrect
                            ? <CheckCircle2 size={20} color="var(--color-success)" />
                            : <XCircle size={20} color="var(--color-error)" />
                        )}
                        <span style={{ fontWeight: 700, fontSize: '0.9rem', color: 'var(--color-surface-50)' }}>
                          {ans.earnedPoints}/{ans.pointValue} điểm
                        </span>
                      </div>
                    </div>

                    {/* Đáp án */}
                    {(ans.questionType === 'MULTIPLE_CHOICE' || ans.questionType === 'TRUE_FALSE') && (
                      <div style={{ background: ans.isCorrect ? 'rgba(16,185,129,0.07)' : 'rgba(239,68,68,0.07)', padding: '12px 16px', borderRadius: 8, borderLeft: `3px solid ${ans.isCorrect ? 'var(--color-success)' : 'var(--color-error)'}` }}>
                        <div style={{ fontSize: '0.8rem', color: 'var(--color-surface-400)', marginBottom: 4 }}>Đáp án sinh viên chọn:</div>
                        <div style={{ fontWeight: 600, color: 'var(--color-surface-50)' }}>{ans.selectedOptionContent ?? '(Không trả lời)'}</div>
                      </div>
                    )}

                    {(ans.questionType === 'SHORT_ANSWER' || ans.questionType === 'ESSAY') && (
                      <div style={{ background: 'var(--color-surface-900)', padding: '12px 16px', borderRadius: 8, border: '1px solid var(--color-surface-700)', fontSize: '0.95rem', color: 'var(--color-surface-50)', lineHeight: 1.7, whiteSpace: 'pre-wrap' }}>
                        {ans.textAnswer || <span style={{ color: 'var(--color-surface-500)', fontStyle: 'italic' }}>(Không có câu trả lời)</span>}
                      </div>
                    )}
                  </div>
                ))}

                {/* Comment area */}
                <div style={{ padding: 24, border: '1px solid var(--color-surface-700)', borderRadius: 12, background: '#fff' }}>
                  <label style={{ display: 'block', fontSize: '0.875rem', fontWeight: 600, color: 'var(--color-surface-200)', marginBottom: 12 }}>
                    Nhận xét của giáo viên (Tùy chọn)
                  </label>
                  <textarea
                    className="input-field" rows={4}
                    placeholder="Nhập nhận xét để gửi cho sinh viên..."
                    value={inputFeedback} onChange={e => setInputFeedback(e.target.value)}
                    style={{ resize: 'vertical' }}
                  />
                </div>
              </div>
            </>
          ) : null}
        </div>
      </div>
    </div>
  )
}

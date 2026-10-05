import { useEffect, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { Question } from '../question-bank/questionApi'
import { examApi } from './examApi'
import { Dialog } from '../../components/Dialog'
import { EmptyState, ErrorState, LoadingState, Pagination } from '../../components/ui'
import { DIFFICULTY_LABEL, QUESTION_TYPE_LABEL } from '../../lib/format'
import SubjectCategoryFields from '../question-bank/SubjectCategoryFields'

/** Picks APPROVED questions for an exam. Publish re-validates approval and freezes versions server-side. */
export default function QuestionPicker({ excluded, onAdd, onClose }: { excluded: string[]; onAdd: (qs: Question[]) => void; onClose: () => void }) {
  const [subjectId, setSubjectId] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [difficulty, setDifficulty] = useState('')
  const [questionType, setQuestionType] = useState('')
  const [search, setSearch] = useState('')
  const [text, setText] = useState('')
  const [page, setPage] = useState(0)
  const [picked, setPicked] = useState<Map<string, Question>>(new Map())
  useEffect(() => { const t = setTimeout(() => { setText(search.trim()); setPage(0) }, 300); return () => clearTimeout(t) }, [search])
  const params = { categoryId: categoryId || undefined, difficulty: difficulty || undefined, questionType: questionType || undefined, text: text || undefined, page, size: 10 }
  const list = useQuery({ queryKey: ['questions', 'approved', params], queryFn: () => examApi.approvedQuestions(params), placeholderData: prev => prev })

  function toggle(q: Question) {
    setPicked(prev => { const next = new Map(prev); if (next.has(q.id)) next.delete(q.id); else next.set(q.id, q); return next })
  }

  return (
    <Dialog size="lg" title="Thêm câu hỏi đã duyệt" onClose={onClose}
      description="Chỉ hiển thị câu hỏi Đã duyệt của bạn. Khi xuất bản, hệ thống kiểm tra lại trạng thái duyệt và cố định phiên bản câu hỏi."
      footer={<>
        <span className="small muted" style={{ marginRight: 'auto' }} role="status">Đã chọn {picked.size} câu</span>
        <button type="button" className="btn btn-secondary" onClick={onClose}>Hủy</button>
        <button type="button" className="btn btn-primary" disabled={picked.size === 0} onClick={() => onAdd([...picked.values()])}>Thêm {picked.size || ''} câu hỏi</button>
      </>}>
      <div className="stack">
        <div style={{ display: 'grid', gap: 12, gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 170px), 1fr))' }}>
          <SubjectCategoryFields allowAll subjectId={subjectId} categoryId={categoryId} onSubject={setSubjectId} onCategory={v => { setCategoryId(v); setPage(0) }} />
          <div className="field">
            <label className="field-label" htmlFor="pick-type">Loại</label>
            <select id="pick-type" className="input" value={questionType} onChange={e => { setQuestionType(e.target.value); setPage(0) }}>
              <option value="">Tất cả loại</option>{Object.entries(QUESTION_TYPE_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </select>
          </div>
          <div className="field">
            <label className="field-label" htmlFor="pick-diff">Độ khó</label>
            <select id="pick-diff" className="input" value={difficulty} onChange={e => { setDifficulty(e.target.value); setPage(0) }}>
              <option value="">Tất cả độ khó</option>{Object.entries(DIFFICULTY_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </select>
          </div>
          <div className="field">
            <label className="field-label" htmlFor="pick-text">Tìm nội dung</label>
            <input id="pick-text" type="search" className="input" maxLength={200} value={search} onChange={e => setSearch(e.target.value)} />
          </div>
        </div>
        <div className="card" style={{ overflow: 'hidden' }}>
          {list.isLoading ? <LoadingState rows={4} /> : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} />
            : list.data!.content.length === 0 ? <EmptyState title="Không có câu hỏi đã duyệt phù hợp" description="Gửi duyệt câu hỏi trong Ngân hàng câu hỏi; câu hỏi chỉ xuất hiện ở đây sau khi được phê duyệt." />
            : list.data!.content.map(q => {
              const already = excluded.includes(q.id)
              return (
                <label key={q.id} className="list-item check" style={{ cursor: already ? 'not-allowed' : 'pointer', opacity: already ? 0.6 : 1 }}>
                  <input type="checkbox" checked={already || picked.has(q.id)} disabled={already} onChange={() => toggle(q)} />
                  <span style={{ minWidth: 0 }}>
                    <span className="xsmall muted">{QUESTION_TYPE_LABEL[q.questionType]} · {DIFFICULTY_LABEL[q.difficultyLevel]} · {q.categoryName} · v{q.questionVersion}{already ? ' · đã có trong đề' : ''}</span>
                    <span className="truncate-2 small" style={{ display: '-webkit-box', color: 'var(--color-ink)' }}>{q.content}</span>
                  </span>
                </label>
              )
            })}
          {list.data && <Pagination label="Phân trang câu hỏi đã duyệt" page={page} totalPages={list.data.totalPages} onChange={setPage} />}
        </div>
      </div>
    </Dialog>
  )
}

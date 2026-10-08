import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { Question } from '../question-bank/questionApi'
import { bankApi } from '../banks/bankApi'
import { BANK_TYPE_LABEL, canPickQuestion } from '../banks/bankModel'
import { Dialog } from '../../components/Dialog'
import { EmptyState, ErrorState, LoadingState, Pagination } from '../../components/ui'
import { DIFFICULTY_LABEL, QUESTION_TYPE_LABEL } from '../../lib/format'

/** Select provider-eligible resources; publication IDs preserve immutable snapshots. */
export default function QuestionPicker({ excluded, onAdd, onClose }: { excluded: string[]; onAdd: (qs: Question[]) => void; onClose: () => void }) {
  const [bankId, setBankId] = useState('')
  const banks = useQuery({ queryKey: ['banks', 'picker'], queryFn: bankApi.allBanks })
  const bank = banks.data?.find(b => b.id === bankId) ?? banks.data?.[0]
  const [difficulty, setDifficulty] = useState('')
  const [questionType, setQuestionType] = useState('')
  const [page, setPage] = useState(0)
  const [picked, setPicked] = useState<Map<string, Question>>(new Map())
  const params = { difficulty: difficulty || undefined, questionType: questionType || undefined, page, size: 10 }
  const list = useQuery({ queryKey: ['questions', 'eligible', bank?.id, params], queryFn: () => bankApi.pickerList(bank!, params), enabled: !!bank, retry: false })

  function toggle(q: Question) {
    if (!canPickQuestion(q)) return
    setPicked(prev => { const next = new Map(prev); if (next.has(q.id)) next.delete(q.id); else next.set(q.id, q); return next })
  }

  return (
    <Dialog size="lg" title="Thêm câu hỏi vào đề" onClose={onClose}
      description="Máy chủ kiểm tra quyền và điều kiện từng ngân hàng. Khi xuất bản, đề giữ bản chụp bất biến."
      footer={<>
        <span className="small muted" style={{ marginRight: 'auto' }} role="status">Đã chọn {picked.size} câu</span>
        <button type="button" className="btn btn-secondary" onClick={onClose}>Hủy</button>
        <button type="button" className="btn btn-primary" disabled={picked.size === 0} onClick={() => onAdd([...picked.values()])}>Thêm {picked.size || ''} câu hỏi</button>
      </>}>
      <div className="stack">
        <div style={{ display: 'grid', gap: 12, gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 170px), 1fr))' }}>
          <div className="field"><label className="field-label" htmlFor="pick-bank">Ngân hàng</label>
            <select id="pick-bank" className="input" value={bank?.id ?? ''} onChange={e => { setBankId(e.target.value); setPage(0) }}>
              {(banks.data ?? []).map(b => <option key={b.id} value={b.id}>{BANK_TYPE_LABEL[b.type]} · {b.name}</option>)}
            </select></div>
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
        </div>
        <div className="card" style={{ overflow: 'hidden' }}>
          {banks.isLoading ? <LoadingState rows={2} /> : banks.isError ? <ErrorState error={banks.error} onRetry={() => void banks.refetch()} /> : !bank ? <EmptyState title="Chưa có ngân hàng truy cập được" /> : list.isLoading ? <LoadingState rows={4} /> : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} />
            : (list.data?.content ?? []).length === 0 ? <EmptyState title="Không có câu hỏi đủ điều kiện phù hợp" description="Kích hoạt câu cá nhân; Public phải đang công khai, Organization cần quyền sử dụng." />
            : (list.data?.content ?? []).map(q => {
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
          {list.data && <Pagination label="Phân trang câu hỏi đủ điều kiện" page={page} totalPages={list.data.totalPages} onChange={setPage} />}
        </div>
      </div>
    </Dialog>
  )
}

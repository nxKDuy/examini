import { useCallback, useRef, useState } from 'react'
import { useBlocker, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { ClipboardCheck, Settings2 } from 'lucide-react'
import './grading.css'
import { gradingApi } from './gradingApi'
import { createDeferredGuard, shouldBlockNavigation } from './gradingFlow'
import SubmissionQueue from './SubmissionQueue'
import SubmissionWorkspace from './SubmissionWorkspace'
import ResultPolicyDialog from './ResultPolicyDialog'
import { examApi } from '../exams/examApi'
import { ConfirmDialog } from '../../components/Dialog'
import { EmptyState, ErrorState, Field, LoadingState, PageHeader } from '../../components/ui'
import { useIsNarrow } from '../../lib/hooks'

const TABS = [{ value: '', label: 'Tất cả' }, { value: 'NEEDS_REVIEW', label: 'Cần chấm tay' }, { value: 'PENDING', label: 'Chờ chấm' }, { value: 'GRADED', label: 'Đã chấm' }]
const RELEASED = [{ value: '', label: 'Công bố: tất cả' }, { value: 'true', label: 'Đã công bố' }, { value: 'false', label: 'Chưa công bố' }]

/** Teacher grading for ONE Exam at a time (the backend queue requires examId). Student-first: pick a submission, grade it item by item. */
export default function GradingPage() {
  const [params, setParams] = useSearchParams()
  const examId = params.get('examId') ?? ''
  const status = params.get('status') ?? ''
  const released = params.get('released') ?? ''
  const selected = params.get('r')
  // The queue page belongs to the current exam/filter combination, so changing any of them starts again at page 0 without a separate reset step.
  const filterKey = `${examId}|${status}|${released}`
  const [pageState, setPageState] = useState({ key: filterKey, n: 0 })
  const page = pageState.key === filterKey ? pageState.n : 0
  const setPage = (n: number) => setPageState({ key: filterKey, n })
  const [policyOpen, setPolicyOpen] = useState(false)
  const dirty = useRef(false)
  // Every URL change (sidebar, Links, browser Back/Forward, exam/filter/submission switches) is held by the router blocker with its full
  // destination; page changes are plain state, so they use a deferred action. Cancel keeps the draft; confirm runs the exact thing asked for.
  const blocker = useBlocker(({ currentLocation, nextLocation }) => shouldBlockNavigation(dirty.current, currentLocation, nextLocation))
  const pageGuard = useRef(createDeferredGuard()).current
  const [, rerender] = useState(0)
  const narrow = useIsNarrow(899)

  const exams = useQuery({ queryKey: ['exams', 'all'], queryFn: examApi.listAll })
  const gradable = (exams.data ?? []).filter(e => e.status !== 'DRAFT')
  const overview = useQuery({ queryKey: ['grading', 'overview', examId], enabled: !!examId, queryFn: () => gradingApi.overview(examId) })
  const policy = useQuery({ queryKey: ['grading', 'policy', examId], enabled: !!examId, queryFn: () => gradingApi.policy(examId), retry: false })
  const list = useQuery({
    queryKey: ['grading', 'list', examId, status, released, page], enabled: !!examId, placeholderData: prev => prev,
    queryFn: () => gradingApi.list({ examId, status: status || undefined, released: released === '' ? undefined : released === 'true', page }),
  })

  const patch = useCallback((changes: Record<string, string | null>) => {
    const next = new URLSearchParams(params)
    for (const [k, v] of Object.entries(changes)) { if (v) next.set(k, v); else next.delete(k) }
    setParams(next, { replace: false })
  }, [params, setParams])
  const select = (id: string | null) => patch({ r: id })
  const onDirty = useCallback((v: boolean) => { dirty.current = v }, [])
  const setFilter = (key: 'status' | 'released', value: string) => patch({ [key]: value, r: null })
  const setExam = (id: string) => patch({ examId: id, status: null, released: null, r: null })
  // Changing the queue page while a draft is open asks first; confirming also closes that submission (its draft is what is being discarded).
  const changePage = (p: number) => {
    const wasDirty = dirty.current
    if (!pageGuard.request(() => { setPage(p); if (wasDirty) patch({ r: null }) }, wasDirty)) rerender(n => n + 1)
  }
  const askingToLeave = blocker.state === 'blocked' || pageGuard.waiting
  const stay = () => { if (blocker.state === 'blocked') blocker.reset(); pageGuard.cancel(); rerender(n => n + 1) }
  const leave = () => { dirty.current = false; if (blocker.state === 'blocked') blocker.proceed(); pageGuard.confirm(); rerender(n => n + 1) }

  const o = overview.data
  const showQueue = !narrow || !selected
  const showWork = !!selected && !!examId

  return (
    <div className="stack">
      <PageHeader title="Chấm bài" description="Chọn kỳ thi, mở từng bài nộp và chấm theo từng câu. Lưu nháp, hoàn tất chấm rồi công bố là ba bước riêng."
        actions={examId && <button type="button" className="btn btn-secondary" onClick={() => setPolicyOpen(true)} disabled={!policy.data}><Settings2 size={16} aria-hidden /> Chính sách kết quả</button>} />
      <section className="card card-body" style={{ display: 'grid', gap: 12 }}>
        <Field label="Kỳ thi" htmlFor="grading-exam" hint="Chỉ Chủ sở hữu và Quản lý của kỳ thi được chấm bài. Hàng chờ chấm theo từng kỳ thi.">
          <select id="grading-exam" className="input" value={examId} onChange={e => setExam(e.target.value)}>
            <option value="">Chọn kỳ thi</option>
            {gradable.map(e => <option key={e.id} value={e.id}>{e.title}</option>)}
          </select>
        </Field>
      </section>

      {!examId ? (
        <section className="card">{exams.isError ? <ErrorState error={exams.error} onRetry={() => void exams.refetch()} />
          : exams.isLoading ? <LoadingState rows={2} />
          : <EmptyState icon={<ClipboardCheck size={30} />} title="Chọn một kỳ thi" description={gradable.length === 0 ? 'Bạn chưa có kỳ thi đã xuất bản. Bài nộp xuất hiện sau khi kỳ thi được mở và thí sinh nộp bài.' : 'Chọn kỳ thi để xem danh sách bài nộp.'} />}</section>
      ) : (
        <>
          {overview.isError ? <section className="card"><ErrorState error={overview.error} onRetry={() => void overview.refetch()} /></section> : (
            <div>
              <div className="grade-overview" role="group" aria-label="Tổng quan toàn kỳ thi">
                {([['Tổng bài nộp', o?.totalSubmissions], ['Cần chấm tay', o?.needsReview], ['Chờ hoàn tất', o?.pending], ['Đã chấm', o?.graded], ['Đã công bố', o?.released], ['Câu chờ chấm tay', o?.pendingManualItems]] as const)
                  .map(([label, n]) => <div key={label}><div className="n">{n ?? '-'}</div><div className="l">{label}</div></div>)}
              </div>
              <p className="xsmall muted" style={{ marginTop: 6 }}>Số liệu của toàn kỳ thi, không chỉ trang đang xem. "Đã công bố" đếm bản công bố nên có thể trùng các nhóm khác (ví dụ bài đang chấm lại), đừng cộng các ô để suy ra tổng.{(o?.invalidated ?? 0) > 0 && <> Có {o!.invalidated} bài đã bị vô hiệu hóa (loại khỏi kết quả hợp lệ, không chấm được).</>}</p>
            </div>
          )}
          {showQueue && (
            <div className="row" style={{ justifyContent: 'space-between' }}>
              <div className="tabs" role="tablist" aria-label="Trạng thái chấm" style={{ border: '1px solid #000' }}>
                {TABS.map(t => <button key={t.value} type="button" role="tab" className="tab" aria-selected={status === t.value} onClick={() => setFilter('status', t.value)}>{t.label}</button>)}
              </div>
              <select className="input" aria-label="Lọc theo công bố" style={{ maxWidth: 200 }} value={released} onChange={e => setFilter('released', e.target.value)}>
                {RELEASED.map(r => <option key={r.value} value={r.value}>{r.label}</option>)}
              </select>
            </div>
          )}
          <div className="grade-layout">
            {showQueue && <SubmissionQueue data={list.data} loading={list.isLoading} error={list.isError ? list.error : null} onRetry={() => void list.refetch()}
              activeId={selected} onSelect={select} page={page} onPage={changePage} status={status} />}
            {showWork
              ? <SubmissionWorkspace key={selected!} id={selected!} policy={policy.data} narrow={narrow} onDirty={onDirty} onBack={() => select(null)} />
              : !narrow && <section className="card" style={{ gridColumn: 'span 2' }}><EmptyState title="Chọn một bài nộp" description="Xem bài làm đóng băng theo đề, chấm từng câu và lưu nháp trước khi hoàn tất." /></section>}
          </div>
        </>
      )}
      {policyOpen && policy.data && <ResultPolicyDialog examId={examId} policy={policy.data} onClose={() => setPolicyOpen(false)} />}
      {askingToLeave && (
        <ConfirmDialog title="Bỏ thay đổi chưa lưu?" tone="danger" confirmLabel="Bỏ thay đổi và tiếp tục" cancelLabel="Ở lại"
          description="Bài đang mở có điểm hoặc nhận xét chưa lưu. Rời đi sẽ mất chúng. Chọn Ở lại để giữ nguyên bản nháp rồi Lưu nháp."
          onCancel={stay} onConfirm={leave} />
      )}
    </div>
  )
}

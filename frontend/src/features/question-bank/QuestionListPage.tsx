import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { BookOpenCheck, CheckCircle2, Download, FileUp, Plus, Sparkles } from 'lucide-react'
import { questionBankApi, type Filters, type Question } from './questionApi'
import { useAuthStore } from '../../store/authStore'
import { audienceOf } from '../../components/layout/navigation'
import { QuestionStatusBadge } from '../../components/status'
import { EmptyState, ErrorState, LoadingState, Notice, PageHeader, Pagination } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { DIFFICULTY_LABEL, QUESTION_TYPE_LABEL, formatDateTime, shortId } from '../../lib/format'
import SubjectCategoryFields from './SubjectCategoryFields'
import QuestionDetail from './QuestionDetail'
import QuestionEditor from './QuestionEditor'
import ExportDialog from './ExportDialog'
import { activateMany, canActivate, type BulkResult } from './bulkActivate'
import { bankApi } from '../banks/bankApi'
import PersonalQuestionSets from '../banks/PersonalQuestionSets'
import { t as tt, useT, i18nMap } from '../../lib/i18n'
import Select from '../../components/Select'

const VIEWS = i18nMap([{ value: 'questions', label: 'Câu hỏi lẻ' }, { value: 'sets', label: 'Bộ câu hỏi' }] as const)
const TABS = i18nMap([{ value: '', label: 'Tất cả' }, { value: 'DRAFT', label: 'Bản nháp' }, { value: 'ACTIVE', label: 'Đang dùng' }, { value: 'ARCHIVED', label: 'Đã lưu trữ' }])

export default function QuestionListPage() {
  useT()
  const user = useAuthStore(s => s.user)
  const admin = audienceOf(user) === 'ADMIN'
  const [subjectId, setSubjectId] = useState('')
  const [search, setSearch] = useState('')
  const [filters, setFilters] = useState<Filters>({ page: 0 })
  const [params, setParams] = useSearchParams()
  // `?q=<id>` opens a question directly (links from copy sync to the new DRAFT children).
  const [selectedId, setSelectedId] = useState(() => params.get('q') ?? '')
  const [editing, setEditing] = useState<Question | null | 'new'>(null)
  // Bulk activation of the actor's own drafts (Personal ACTIVE needs no review).
  const [checked, setChecked] = useState<Set<string>>(new Set())
  const [progress, setProgress] = useState(0)
  const [bulk, setBulk] = useState<BulkResult | null>(null)
  const [exporting, setExporting] = useState(false)
  const qc = useQueryClient()
  const detailRef = useRef<HTMLDivElement>(null)
  // Single questions are the default view; Question Sets live in their own tab so the list is never pushed below the fold.
  const view = !admin && params.get('view') === 'sets' ? 'sets' : 'questions'

  useEffect(() => {
    const t = setTimeout(() => setFilters(f => (f.text ?? '') === search.trim() ? f : { ...f, text: search.trim() || undefined, page: 0 }), 300)
    return () => clearTimeout(t)
  }, [search])

  const list = useQuery({ queryKey: ['questions', user?.id, filters], queryFn: () => questionBankApi.getQuestions(filters), placeholderData: prev => prev })
  const set = (key: keyof Filters, value: string) => setFilters(f => ({ ...f, [key]: value || undefined, page: 0 }))
  const drafts = (list.data?.content ?? []).filter(q => canActivate(q, user?.id))
  const picked = drafts.filter(q => checked.has(q.id))
  const toggle = (id: string, on: boolean) => setChecked(prev => { const next = new Set(prev); if (on) next.add(id); else next.delete(id); return next })
  const activateAll = useMutation({
    mutationFn: () => { setProgress(0); setBulk(null); return activateMany(picked, bankApi.activate, errorText, setProgress) },
    onSuccess: async r => { setBulk(r); setChecked(new Set(r.failed.map(f => f.id))); await qc.invalidateQueries({ queryKey: ['questions'] }); await qc.invalidateQueries({ queryKey: ['question'] }) },
  })

  // Laptop: the detail pane sits beside the list, so open the first question instead of an empty pane.
  const firstId = list.data?.content[0]?.id
  useEffect(() => {
    if (!selectedId && firstId && window.matchMedia('(min-width: 1024px)').matches) setSelectedId(firstId)
  }, [firstId, selectedId])

  /** ↑/↓ (or j/k) move through the list when focus is not in a form field; Enter or E edits the open question. */
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const el = e.target as HTMLElement
      if (e.altKey || e.ctrlKey || e.metaKey || el.closest('input, textarea, select, [contenteditable=true], [role=dialog]')) return
      const ids = list.data?.content.map(q => q.id) ?? []
      if (!ids.length) return
      const at = ids.indexOf(selectedId)
      const step = e.key === 'ArrowDown' || e.key === 'j' ? 1 : e.key === 'ArrowUp' || e.key === 'k' ? -1 : 0
      if (!step) return
      e.preventDefault()
      const next = ids[Math.min(ids.length - 1, Math.max(0, at < 0 ? 0 : at + step))]
      setSelectedId(next)
      document.querySelector<HTMLElement>(`[data-qid="${next}"]`)?.scrollIntoView({ block: 'nearest' })
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [list.data, selectedId])

  function select(id: string) {
    setSelectedId(id)
    setTimeout(() => { const top = detailRef.current?.getBoundingClientRect().top ?? 0; if (top < 64 || top > window.innerHeight - 120) detailRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }) }, 50)
  }

  return (
    <div className="stack">
      <PageHeader
        title={admin ? tt('Câu hỏi') : tt('Kho cá nhân')}
        description={admin ? tt('Soạn bản nháp, kích hoạt để dùng.') : tt('Soạn bản nháp, kích hoạt, gom thành bộ và lưu trữ. Để đăng câu hoặc bộ lên cộng đồng, dùng nút + ở Ngân hàng công khai.')}
        actions={!admin && <>
          <Link to="/questions/imports" className="btn btn-secondary"><FileUp size={16} aria-hidden /> {tt('Nhập tài liệu')}</Link>
          <Link to="/questions/imports?new=ai" className="btn btn-secondary"><Sparkles size={16} aria-hidden /> {tt('Tạo bằng AI')}</Link>
          <button type="button" className="btn btn-secondary" onClick={() => setExporting(true)}><Download size={16} aria-hidden /> {tt('Xuất')}</button>
          <button type="button" className="btn btn-primary" onClick={() => setEditing('new')}><Plus size={16} aria-hidden /> {tt('Tạo câu hỏi')}</button>
        </>} />

      {!admin && <div className="tabs" role="tablist" aria-label={tt('Nội dung kho cá nhân')}>
        {VIEWS.map(v => (
          <button key={v.value} type="button" role="tab" className="tab" aria-selected={view === v.value}
            onClick={() => setParams(v.value === 'sets' ? { view: 'sets' } : {}, { replace: true })}>{v.label}</button>
        ))}
      </div>}

      {view === 'sets' ? <PersonalQuestionSets /> : <>
      <section className="card" aria-label={tt('Bộ lọc câu hỏi')}>
        <div className="tabs" role="tablist" aria-label={tt('Trạng thái')}>
          {TABS.map(t => (
            <button key={t.value} type="button" role="tab" className="tab" aria-selected={(filters.status ?? '') === t.value} onClick={() => set('status', t.value)}>{t.label}</button>
          ))}
        </div>
        <div className="card-body" style={{ display: 'grid', gap: 12, gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 180px), 1fr))', paddingTop: 16, paddingBottom: 16 }}>
          <SubjectCategoryFields allowAll subjectId={subjectId} categoryId={filters.categoryId ?? ''} onSubject={setSubjectId} onCategory={v => set('categoryId', v)} />
          <div className="field">
            <label className="field-label" htmlFor="f-type">{tt('Loại')}</label>
            <Select id="f-type" className="input" value={filters.questionType ?? ''} onChange={e => set('questionType', e.target.value)}>
              <option value="">{tt('Tất cả loại')}</option>
              {Object.entries(QUESTION_TYPE_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </Select>
          </div>
          <div className="field">
            <label className="field-label" htmlFor="f-diff">{tt('Độ khó')}</label>
            <Select id="f-diff" className="input" value={filters.difficulty ?? ''} onChange={e => set('difficulty', e.target.value)}>
              <option value="">{tt('Tất cả độ khó')}</option>
              {Object.entries(DIFFICULTY_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </Select>
          </div>
          <div className="field">
            <label className="field-label" htmlFor="f-text">{tt('Tìm trong nội dung')}</label>
            <input id="f-text" type="search" className="input" maxLength={200} value={search} onChange={e => setSearch(e.target.value)} />
          </div>
        </div>
      </section>

      <div className="split">
        <section className="card" aria-label={tt('Danh sách câu hỏi')} style={{ overflow: 'hidden' }}>
          <div className="card-header"><h2 className="small" style={{ fontSize: '0.9375rem' }}>{list.data ? tt('{0} câu hỏi', { 0: list.data.totalElements }) : tt('Câu hỏi')}</h2>
            <span className="xsmall muted hide-sm" aria-hidden>{tt('↑ ↓ để duyệt')}</span>
            {list.isFetching && !list.isLoading && <span className="xsmall muted" role="status">{tt('Đang cập nhật…')}</span>}</div>
          {!admin && drafts.length > 0 && <div className="bulk-bar" role="group" aria-label={tt('Kích hoạt hàng loạt')}>
            <label className="check small"><input type="checkbox" checked={picked.length === drafts.length}
              ref={el => { if (el) el.indeterminate = picked.length > 0 && picked.length < drafts.length }}
              onChange={e => setChecked(e.target.checked ? new Set(drafts.map(q => q.id)) : new Set())} /> {tt('Chọn {0} bản nháp trên trang', { 0: drafts.length })}</label>
            <button type="button" className="btn btn-primary btn-sm" disabled={!picked.length || activateAll.isPending} aria-busy={activateAll.isPending} onClick={() => activateAll.mutate()}>
              <CheckCircle2 size={14} aria-hidden /> {activateAll.isPending ? tt('Đang kích hoạt {0}/{1}…', { 0: progress, 1: picked.length }) : tt('Kích hoạt {0} câu', { 0: picked.length })}</button>
          </div>}
          {bulk && <div style={{ padding: '0 16px' }}><Notice tone={bulk.failed.length ? 'warning' : 'success'} live
            title={tt('Đã kích hoạt {0} câu; có thể dùng ngay cho đề thi.', { 0: bulk.activated.length })}>
            {bulk.failed.length > 0 && <><span>{tt('{0} câu chưa kích hoạt được (vẫn được chọn để sửa):', { 0: bulk.failed.length })}</span>
              <ul style={{ margin: '4px 0 0', paddingLeft: 18 }}>{bulk.failed.map(f => <li key={f.id} className="small"><span className="truncate-2">{f.content}</span> — {f.reason}</li>)}</ul></>}
          </Notice></div>}
          {list.isLoading ? <LoadingState rows={6} /> : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} />
            : list.data!.content.length === 0
              ? <EmptyState icon={<BookOpenCheck size={30} />} title={tt('Không có câu hỏi phù hợp')}
                  description={tt('Đổi bộ lọc hoặc tạo câu hỏi mới.')}
                  action={!admin && <button type="button" className="btn btn-primary btn-sm" onClick={() => setEditing('new')}><Plus size={14} aria-hidden /> {tt('Tạo câu hỏi')}</button>} />
              : <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                  {list.data!.content.map(item => (
                    <li key={item.id} className="list-row">{canActivate(item, user?.id) && !admin &&
                      <label className="check list-check"><input type="checkbox" aria-label={tt('Chọn để kích hoạt')} checked={checked.has(item.id)} onChange={e => toggle(item.id, e.target.checked)} /></label>}
                      <button type="button" className="list-item" data-qid={item.id} aria-current={selectedId === item.id} onClick={() => select(item.id)}>
                      <div className="row" style={{ marginBottom: 6 }}>
                        <QuestionStatusBadge status={item.status} />
                        <span className="xsmall muted">{QUESTION_TYPE_LABEL[item.questionType]} · {DIFFICULTY_LABEL[item.difficultyLevel]} · v{item.questionVersion}</span>
                      </div>
                      <div className="truncate-2 small" style={{ color: 'var(--color-ink)' }}>{item.content}</div>
                      <div className="xsmall muted" style={{ marginTop: 4 }}>
                        {item.createdBy === user?.id ? tt('Của bạn') : tt('Tác giả {0}', { 0: shortId(item.createdBy) })} · {formatDateTime(item.updatedAt)}
                      </div>
                    </button></li>
                  ))}
                </ul>}
          {list.data && <Pagination label={tt('Phân trang câu hỏi')} page={filters.page ?? 0} totalPages={list.data.totalPages} onChange={p => setFilters(f => ({ ...f, page: p }))} />}
        </section>

        <section ref={detailRef} className="card card-body sticky-pane" aria-label={tt('Chi tiết câu hỏi')} style={{ minHeight: 200 }}>
          {selectedId ? <QuestionDetail key={selectedId} id={selectedId} onEdit={q => setEditing(q)} />
            : <EmptyState title={tt('Chọn một câu hỏi')} description={tt('Chi tiết, đáp án, nguồn gốc và lịch sử phiên bản sẽ hiển thị ở đây.')} />}
        </section>
      </div>
      </>}

      {exporting && <ExportDialog filters={filters} onClose={() => setExporting(false)} />}
      {editing && <QuestionEditor initial={editing === 'new' ? null : editing} onClose={() => setEditing(null)}
        onSaved={q => { setEditing(null); setSelectedId(q.id) }} />}
    </div>
  )
}

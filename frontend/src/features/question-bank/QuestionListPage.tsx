import { useEffect, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BookOpenCheck, FileUp, Plus } from 'lucide-react'
import { questionBankApi, type Filters, type ImportResult, type Question } from './questionApi'
import { useAuthStore } from '../../store/authStore'
import { audienceOf } from '../../components/layout/navigation'
import { QuestionStatusBadge } from '../../components/status'
import { Dialog } from '../../components/Dialog'
import { EmptyState, ErrorState, LoadingState, Notice, PageHeader, Pagination } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { DIFFICULTY_LABEL, QUESTION_TYPE_LABEL, formatDateTime, shortId } from '../../lib/format'
import SubjectCategoryFields from './SubjectCategoryFields'
import QuestionDetail from './QuestionDetail'
import QuestionEditor from './QuestionEditor'

const TABS = [{ value: '', label: 'Tất cả' }, { value: 'DRAFT', label: 'Bản nháp' }, { value: 'ACTIVE', label: 'Đang dùng' }, { value: 'ARCHIVED', label: 'Đã lưu trữ' }]

export default function QuestionListPage() {
  const user = useAuthStore(s => s.user)
  const admin = audienceOf(user) === 'ADMIN'
  const [subjectId, setSubjectId] = useState('')
  const [search, setSearch] = useState('')
  const [filters, setFilters] = useState<Filters>({ page: 0 })
  const [selectedId, setSelectedId] = useState('')
  const [editing, setEditing] = useState<Question | null | 'new'>(null)
  const [importing, setImporting] = useState(false)
  const detailRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const t = setTimeout(() => setFilters(f => (f.text ?? '') === search.trim() ? f : { ...f, text: search.trim() || undefined, page: 0 }), 300)
    return () => clearTimeout(t)
  }, [search])

  const list = useQuery({ queryKey: ['questions', user?.id, filters], queryFn: () => questionBankApi.getQuestions(filters), placeholderData: prev => prev })
  const set = (key: keyof Filters, value: string) => setFilters(f => ({ ...f, [key]: value || undefined, page: 0 }))

  function select(id: string) {
    setSelectedId(id)
    setTimeout(() => { const top = detailRef.current?.getBoundingClientRect().top ?? 0; if (top < 64 || top > window.innerHeight - 120) detailRef.current?.scrollIntoView({ behavior: 'smooth', block: 'start' }) }, 50)
  }

  return (
    <div className="stack">
      <PageHeader
        title={admin ? 'Câu hỏi' : 'Kho cá nhân'}
        description="Soạn bản nháp, kích hoạt để dùng. Gửi vào tổ chức hoặc xuất bản công khai từ câu cá nhân đang dùng; câu gốc giữ độc lập."
        actions={!admin && <>
          <button type="button" className="btn btn-secondary" onClick={() => setImporting(true)}><FileUp size={16} aria-hidden /> Nhập CSV</button>
          <button type="button" className="btn btn-primary" onClick={() => setEditing('new')}><Plus size={16} aria-hidden /> Tạo câu hỏi</button>
        </>} />

      <section className="card" aria-label="Bộ lọc câu hỏi">
        <div className="tabs" role="tablist" aria-label="Trạng thái">
          {TABS.map(t => (
            <button key={t.value} type="button" role="tab" className="tab" aria-selected={(filters.status ?? '') === t.value} onClick={() => set('status', t.value)}>{t.label}</button>
          ))}
        </div>
        <div className="card-body" style={{ display: 'grid', gap: 12, gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 180px), 1fr))', paddingTop: 16, paddingBottom: 16 }}>
          <SubjectCategoryFields allowAll subjectId={subjectId} categoryId={filters.categoryId ?? ''} onSubject={setSubjectId} onCategory={v => set('categoryId', v)} />
          <div className="field">
            <label className="field-label" htmlFor="f-type">Loại</label>
            <select id="f-type" className="input" value={filters.questionType ?? ''} onChange={e => set('questionType', e.target.value)}>
              <option value="">Tất cả loại</option>
              {Object.entries(QUESTION_TYPE_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </select>
          </div>
          <div className="field">
            <label className="field-label" htmlFor="f-diff">Độ khó</label>
            <select id="f-diff" className="input" value={filters.difficulty ?? ''} onChange={e => set('difficulty', e.target.value)}>
              <option value="">Tất cả độ khó</option>
              {Object.entries(DIFFICULTY_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
            </select>
          </div>
          <div className="field">
            <label className="field-label" htmlFor="f-text">Tìm trong nội dung</label>
            <input id="f-text" type="search" className="input" maxLength={200} value={search} onChange={e => setSearch(e.target.value)} />
          </div>
        </div>
      </section>

      <div className="split">
        <section className="card" aria-label="Danh sách câu hỏi" style={{ overflow: 'hidden' }}>
          <div className="card-header"><h2 className="small" style={{ fontSize: '0.9375rem' }}>{list.data ? `${list.data.totalElements} câu hỏi` : 'Câu hỏi'}</h2>
            {list.isFetching && !list.isLoading && <span className="xsmall muted" role="status">Đang cập nhật…</span>}</div>
          {list.isLoading ? <LoadingState rows={6} /> : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} />
            : list.data!.content.length === 0
              ? <EmptyState icon={<BookOpenCheck size={30} />} title={'Không có câu hỏi phù hợp'}
                  description={'Đổi bộ lọc hoặc tạo câu hỏi mới.'}
                  action={!admin && <button type="button" className="btn btn-primary btn-sm" onClick={() => setEditing('new')}><Plus size={14} aria-hidden /> Tạo câu hỏi</button>} />
              : <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                  {list.data!.content.map(item => (
                    <li key={item.id}><button type="button" className="list-item" aria-current={selectedId === item.id} onClick={() => select(item.id)}>
                      <div className="row" style={{ marginBottom: 6 }}>
                        <QuestionStatusBadge status={item.status} />
                        <span className="xsmall muted">{QUESTION_TYPE_LABEL[item.questionType]} · {DIFFICULTY_LABEL[item.difficultyLevel]} · v{item.questionVersion}</span>
                      </div>
                      <div className="truncate-2 small" style={{ color: 'var(--color-ink)' }}>{item.content}</div>
                      <div className="xsmall muted" style={{ marginTop: 4 }}>
                        {item.createdBy === user?.id ? 'Của bạn' : `Tác giả ${shortId(item.createdBy)}`} · {formatDateTime(item.updatedAt)}
                      </div>
                    </button></li>
                  ))}
                </ul>}
          {list.data && <Pagination label="Phân trang câu hỏi" page={filters.page ?? 0} totalPages={list.data.totalPages} onChange={p => setFilters(f => ({ ...f, page: p }))} />}
        </section>

        <section ref={detailRef} className="card card-body sticky-pane" aria-label="Chi tiết câu hỏi" style={{ minHeight: 200 }}>
          {selectedId ? <QuestionDetail key={selectedId} id={selectedId} onEdit={q => setEditing(q)} />
            : <EmptyState title="Chọn một câu hỏi" description="Chi tiết, đáp án, nguồn gốc và lịch sử phiên bản sẽ hiển thị ở đây." />}
        </section>
      </div>

      {editing && <QuestionEditor initial={editing === 'new' ? null : editing} onClose={() => setEditing(null)}
        onSaved={q => { setEditing(null); setSelectedId(q.id) }} />}
      {importing && <ImportDialog onClose={() => setImporting(false)} />}
    </div>
  )
}

function ImportDialog({ onClose }: { onClose: () => void }) {
  const qc = useQueryClient()
  const [subjectId, setSubjectId] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [file, setFile] = useState<File | null>(null)
  const [result, setResult] = useState<ImportResult | null>(null)
  const run = useMutation({
    mutationFn: () => questionBankApi.importCsv(file!, categoryId),
    onSuccess: async r => { setResult(r); await qc.invalidateQueries({ queryKey: ['questions'] }) },
  })
  return (
    <Dialog title="Nhập câu hỏi từ CSV" onClose={onClose} busy={run.isPending}
      description="Câu hỏi nhập vào ở trạng thái Bản nháp. Tối đa 1000 dòng, 10MB."
      footer={<>
        <button type="button" className="btn btn-secondary" onClick={onClose} disabled={run.isPending}>{result ? 'Đóng' : 'Hủy'}</button>
        <button type="button" className="btn btn-primary" disabled={!file || !categoryId || run.isPending} onClick={() => run.mutate()}>{run.isPending ? 'Đang nhập…' : 'Nhập'}</button>
      </>}>
      <div className="stack">
        <div className="grid-2"><SubjectCategoryFields subjectId={subjectId} categoryId={categoryId} onSubject={setSubjectId} onCategory={setCategoryId} required /></div>
        <div className="field">
          <label className="field-label" htmlFor="csv-file">Tệp CSV</label>
          <input id="csv-file" type="file" accept=".csv,text/csv" className="input" onChange={e => { setFile(e.target.files?.[0] ?? null); setResult(null) }} />
          <span className="field-hint">Cột bắt buộc: content, type, difficulty. Lựa chọn: option1, correct1, option2, correct2… (true/false). Trả lời ngắn và tự luận dùng cột expectedAnswer.</span>
        </div>
        {run.isError && <Notice tone="danger" live>{errorText(run.error)}</Notice>}
        {result && (
          <Notice tone={result.failed ? 'warning' : 'success'} live title={`Đã nhập ${result.imported}/${result.totalRows} dòng${result.failed ? `, ${result.failed} dòng lỗi` : ''}`}>
            {result.errors.length > 0 && <ul style={{ margin: '6px 0 0', paddingLeft: 18 }}>{result.errors.slice(0, 20).map((e, i) => <li key={i}>{e}</li>)}</ul>}
          </Notice>
        )}
      </div>
    </Dialog>
  )
}

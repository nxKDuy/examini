import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, CheckCircle2, Plus, ScanText, Trash2 } from 'lucide-react'
import { importApi, type ImportItem, type ImportResult, type ItemFilter } from './importApi'
import { FILTERS, FLAG_LABEL, dirty, draftOf, isOcr, itemState, lowConfidence, mustReview, optionLetter, patchOf, type Draft } from './importModel'
import SubjectCategoryFields from '../question-bank/SubjectCategoryFields'
import { ConfirmDialog } from '../../components/Dialog'
import { Badge, EmptyState, ErrorState, Field, LoadingState, Notice, PageHeader, Spinner } from '../../components/ui'
import { errorStatus, errorText } from '../../lib/errors'
import { DIFFICULTY_LABEL, QUESTION_TYPE_LABEL, formatDateTime } from '../../lib/format'
import './import.css'

const KIND_LABEL = { DOCX: 'Word (DOCX)', PDF: 'PDF', IMAGE: 'Ảnh' } as const

/** Review → edit → validate → import (Personal DRAFT). Nothing is activated or published. */
export default function ImportJobPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const [filter, setFilter] = useState<ItemFilter>('ALL')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [result, setResult] = useState<ImportResult | null>(null)
  const [flash, setFlash] = useState<{ tone: 'success' | 'danger' | 'info'; text: string } | null>(null)
  const [discarding, setDiscarding] = useState(false)
  const job = useQuery({ queryKey: ['import-job', id], queryFn: () => importApi.job(id!), refetchInterval: q => q.state.data?.status === 'PROCESSING' ? 1500 : false })
  const ready = job.data?.status === 'READY'
  const items = useQuery({ queryKey: ['import-items', id, filter], queryFn: () => importApi.items(id!, filter, 0, 100), enabled: !!job.data && job.data.status !== 'PROCESSING' })
  const refresh = () => Promise.all([qc.invalidateQueries({ queryKey: ['import-job', id] }), qc.invalidateQueries({ queryKey: ['import-items', id] })])
  const selected = items.data?.content.find(i => i.id === selectedId) ?? null

  const validate = useMutation({ mutationFn: () => importApi.validate(id!), onSuccess: async s => { await refresh(); setFlash({ tone: 'info', text: `Đã kiểm tra lại: ${s.valid}/${s.total} hợp lệ, ${s.needsReview} cần xem lại, ${s.importable} sẵn sàng nhập.` }) }, onError: e => setFlash({ tone: 'danger', text: errorText(e) }) })
  const run = useMutation({ mutationFn: () => importApi.importItems(id!), onSuccess: async r => { setResult(r); setFlash(null); await refresh(); await qc.invalidateQueries({ queryKey: ['questions'] }) }, onError: e => setFlash({ tone: 'danger', text: errorText(e) }) })
  const discard = useMutation({ mutationFn: () => importApi.discard(id!), onSuccess: () => navigate('/questions/imports'), onError: e => { setDiscarding(false); setFlash({ tone: 'danger', text: errorText(e) }) } })

  if (job.isLoading) return <LoadingState rows={5} />
  if (job.isError || !job.data) return <ErrorState error={job.error} onRetry={() => void job.refetch()} />
  const j = job.data, s = j.summary

  return (
    <div className="stack-lg">
      <PageHeader title={j.fileName} crumbs={[{ label: 'Kho cá nhân', to: '/questions' }, { label: 'Nhập tài liệu', to: '/questions/imports' }, { label: j.fileName }]}
        description={`${KIND_LABEL[j.fileKind]} · ${j.pageCount} trang${j.ocrPageCount ? ` · ${j.ocrPageCount} trang nhận dạng ảnh (OCR)` : ''} · tải lên ${formatDateTime(j.createdAt)}`}
        actions={ready && <>
          <button type="button" className="btn btn-danger-ghost" onClick={() => setDiscarding(true)}><Trash2 size={16} aria-hidden /> Hủy lô</button>
          <button type="button" className="btn btn-secondary" disabled={validate.isPending} onClick={() => validate.mutate()}><CheckCircle2 size={16} aria-hidden /> Kiểm tra lại</button>
          <button type="button" className="btn btn-primary" disabled={!s.importable || run.isPending} aria-busy={run.isPending} onClick={() => run.mutate()}><Plus size={16} aria-hidden /> Nhập {s.importable} câu (bản nháp)</button>
        </>} />

      {j.status === 'PROCESSING' && <Notice tone="info" live title="Đang xử lý tài liệu"><span className="row" style={{ gap: 8 }}><Spinner size={14} /> Đang đọc chữ và tách câu hỏi. Trang tự cập nhật khi xong.</span></Notice>}
      {j.status === 'FAILED' && <Notice tone="danger" title="Không xử lý được tài liệu">{j.failureReason || 'Tài liệu không đọc được.'} Hãy kiểm tra tệp rồi tải lên lại.</Notice>}
      {j.status === 'DISCARDED' && <Notice tone="warning">Lô này đã bị hủy; các câu đã nhập trước đó vẫn giữ trong kho.</Notice>}
      {j.warnings.length > 0 && <Notice tone="warning" title="Lưu ý khi đọc tài liệu"><ul style={{ margin: 0, paddingLeft: 18 }}>{j.warnings.map(w => <li key={w} className="small">{w}</li>)}</ul></Notice>}
      {flash && <Notice tone={flash.tone} live>{flash.text}</Notice>}
      {result && <Notice tone="success" live title={`Đã nhập ${result.imported.length} câu vào Kho cá nhân dưới dạng bản nháp`}
        action={<Link className="btn btn-secondary btn-sm" to="/questions">Mở Kho cá nhân</Link>}>
        Câu chưa được kích hoạt hay công khai; kích hoạt từng câu khi đã sẵn sàng.{result.skipped.length ? ` ${result.skipped.length} câu bỏ qua (chưa hợp lệ, cần xem lại hoặc đã loại).` : ''}</Notice>}

      {j.status !== 'PROCESSING' && <>
        <div className="tabs" role="tablist" aria-label="Lọc câu">
          {FILTERS.map(f => <button key={f.value} type="button" role="tab" className="tab" aria-selected={filter === f.value} onClick={() => { setFilter(f.value); setSelectedId(null) }}>{f.label}<span className="count">{f.count(s)}</span></button>)}
        </div>
        <div className="imp-layout" data-panel={selected ? 'open' : 'closed'}>
          <section className="card" aria-label="Các câu nhận dạng được" style={{ overflow: 'hidden' }}>
            {items.isLoading ? <LoadingState rows={4} /> : items.isError ? <ErrorState error={items.error} onRetry={() => void items.refetch()} />
              : !items.data?.content.length ? <EmptyState title="Không có câu trong mục này" />
              : <ol className="imp-list">{items.data.content.map(i => <ItemRow key={i.id} item={i} active={i.id === selectedId} onOpen={() => setSelectedId(i.id)} />)}</ol>}
          </section>
          {selected && <ItemEditor key={selected.id} jobId={j.id} item={selected} editable={ready && !selected.importedQuestionId}
            onClose={() => setSelectedId(null)} onSaved={async () => { await refresh() }} />}
        </div>
      </>}
      {discarding && <ConfirmDialog title="Hủy lô nhập này?" tone="danger" confirmLabel="Hủy lô" busy={discard.isPending}
        description="Các câu chưa nhập sẽ không sửa được nữa. Câu đã nhập vào kho vẫn giữ nguyên." onConfirm={() => discard.mutate()} onCancel={() => setDiscarding(false)} />}
    </div>
  )
}

function ItemRow({ item, active, onOpen }: { item: ImportItem; active: boolean; onOpen: () => void }) {
  const st = itemState(item)
  const attention = mustReview(item)
  return (
    <li>
      <button type="button" className="imp-row" aria-current={active} data-attention={attention || undefined} onClick={onOpen}>
        <span className="row" style={{ gap: 6 }}>
          <strong className="small">Câu {item.position + 1}</strong>
          <Badge tone={st.tone}>{st.label}</Badge>
          {isOcr(item) && <Badge tone="warning" icon={<ScanText size={12} aria-hidden />}>OCR</Badge>}
          {lowConfidence(item) && <Badge tone="warning">Tin cậy {Math.round(item.confidence * 100)}%</Badge>}
          {item.reviewed && <Badge>Đã xem</Badge>}
        </span>
        <span className="small truncate-2" style={{ display: '-webkit-box' }}>{item.content || '(chưa có nội dung)'}</span>
        <span className="xsmall muted">{QUESTION_TYPE_LABEL[item.questionType]} · {DIFFICULTY_LABEL[item.difficultyLevel]} · trang {item.source.page ?? '?'}{item.errors.length ? ` · ${item.errors.length} lỗi` : ''}</span>
      </button>
    </li>
  )
}

function ItemEditor({ jobId, item, editable, onClose, onSaved }: { jobId: string; item: ImportItem; editable: boolean; onClose: () => void; onSaved: () => Promise<void> }) {
  const [d, setD] = useState<Draft>(() => draftOf(item))
  const [subjectId, setSubjectId] = useState('')
  const [notice, setNotice] = useState<{ tone: 'success' | 'danger' | 'warning'; text: string } | null>(null)
  // A saved (or reloaded) version replaces the draft; the save notice stays visible.
  // eslint-disable-next-line react-hooks/exhaustive-deps -- only a new server version replaces the draft, never a background refetch
  useEffect(() => { setD(draftOf(item)) }, [item.version])
  const save = useMutation({
    mutationFn: (extra: { reviewed?: boolean; excluded?: boolean }) => importApi.update(jobId, item.id, patchOf(item, d, extra)),
    onSuccess: async (r, extra) => {
      await onSaved()
      setNotice({ tone: r.valid ? 'success' : 'warning', text: extra.excluded ? 'Đã loại câu này khỏi lần nhập.' : extra.excluded === false ? 'Đã đưa câu trở lại danh sách nhập.' : r.valid ? (r.importable ? 'Đã lưu. Câu sẵn sàng nhập.' : 'Đã lưu. Câu còn cần đánh dấu đã xem lại.') : 'Đã lưu nhưng câu chưa hợp lệ; xem lỗi bên dưới.' })
    },
    onError: async e => { if (errorStatus(e) === 409) { setNotice({ tone: 'warning', text: 'Câu đã thay đổi ở nơi khác. Đã tải lại; kiểm tra rồi lưu lại.' }); await onSaved() } else setNotice({ tone: 'danger', text: errorText(e) }) },
  })
  const choice = d.questionType === 'MULTIPLE_CHOICE' || d.questionType === 'TRUE_FALSE'
  const setOpt = (n: number, p: Partial<Draft['options'][number]>) => setD(x => ({ ...x, options: x.options.map((o, i) => i === n ? { ...o, ...p } : o) }))
  const changed = dirty(item, d)
  const attention = mustReview(item)

  return (
    <aside className="imp-panel" aria-label={`Sửa câu ${item.position + 1}`}>
      <div className="imp-panel-head"><h2 style={{ fontSize: '1.15rem' }}>Câu {item.position + 1}</h2><button type="button" className="btn btn-ghost btn-sm" onClick={onClose}><ArrowLeft size={14} aria-hidden /> Đóng</button></div>
      <div className="imp-panel-body stack">
        {attention && <Notice tone="warning" title="Bắt buộc xem lại">{isOcr(item) ? 'Câu được nhận dạng từ ảnh (OCR) nên có thể sai chữ. ' : ''}{lowConfidence(item) ? `Độ tin cậy ${Math.round(item.confidence * 100)}%. ` : ''}So với nội dung gốc bên dưới, sửa nếu cần rồi bấm “Lưu và đánh dấu đã xem”.</Notice>}
        {item.importedQuestionId && <Notice tone="success">Đã nhập thành câu bản nháp trong Kho cá nhân. <Link to={`/questions?q=${item.importedQuestionId}`}>Mở câu hỏi</Link></Notice>}
        {notice && <Notice tone={notice.tone} live>{notice.text}</Notice>}
        {item.flags.length > 0 && <div className="row" style={{ gap: 6 }}>{item.flags.map(f => <Badge key={f} tone="warning">{FLAG_LABEL[f] ?? f}</Badge>)}</div>}
        {item.errors.length > 0 && <ul className="imp-errors">{item.errors.map(e => <li key={e.code} className="field-error">{e.message}</li>)}</ul>}
        <fieldset className="stack" disabled={!editable || save.isPending} style={{ border: 0, padding: 0, margin: 0 }}>
          <Field label="Nội dung câu hỏi" htmlFor="imp-content" required><textarea id="imp-content" className="input" rows={4} value={d.content} onChange={e => setD({ ...d, content: e.target.value })} /></Field>
          <div className="grid-2">
            <Field label="Loại" htmlFor="imp-type"><select id="imp-type" className="input" value={d.questionType} onChange={e => setD({ ...d, questionType: e.target.value as Draft['questionType'] })}>{Object.entries(QUESTION_TYPE_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></Field>
            <Field label="Độ khó" htmlFor="imp-diff"><select id="imp-diff" className="input" value={d.difficultyLevel} onChange={e => setD({ ...d, difficultyLevel: e.target.value as Draft['difficultyLevel'] })}>{Object.entries(DIFFICULTY_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></Field>
          </div>
          <div className="grid-2"><SubjectCategoryFields subjectId={subjectId} categoryId={d.categoryId} onSubject={setSubjectId} onCategory={id => setD(x => ({ ...x, categoryId: id }))} required /></div>
          {choice && <fieldset className="stack-sm" style={{ border: 0, padding: 0, margin: 0 }}>
            <legend className="field-label">Phương án (đánh dấu đáp án đúng)</legend>
            {d.options.map((o, n) => <div key={n} className="row imp-option" style={{ flexWrap: 'nowrap', gap: 8 }}>
              <span className="option-key" aria-hidden>{optionLetter(n)}</span>
              <input className="input" aria-label={`Phương án ${optionLetter(n)}`} value={o.content} onChange={e => setOpt(n, { content: e.target.value })} />
              <label className="check small" style={{ whiteSpace: 'nowrap' }}><input type="checkbox" checked={o.correct} onChange={e => setOpt(n, { correct: e.target.checked })} /> Đúng</label>
              <button type="button" className="btn btn-ghost btn-icon btn-sm" aria-label={`Bỏ phương án ${optionLetter(n)}`} onClick={() => setD(x => ({ ...x, options: x.options.filter((_, i) => i !== n) }))}><Trash2 size={14} /></button>
            </div>)}
            {d.options.length < 8 && <button type="button" className="btn btn-ghost btn-sm" style={{ alignSelf: 'flex-start' }} onClick={() => setD(x => ({ ...x, options: [...x.options, { content: '', correct: false, displayOrder: x.options.length }] }))}><Plus size={14} aria-hidden /> Thêm phương án</button>}
            {!d.options.some(o => o.correct) && <p className="field-error">Chưa có đáp án đúng. Hệ thống không tự đoán; hãy chọn đáp án.</p>}
          </fieldset>}
          {!choice && <Field label={d.questionType === 'ESSAY' ? 'Hướng dẫn chấm' : 'Đáp án chuẩn'} htmlFor="imp-answer"><textarea id="imp-answer" className="input" rows={2} value={d.expectedAnswer} onChange={e => setD({ ...d, expectedAnswer: e.target.value })} /></Field>}
          <Field label="Giải thích" htmlFor="imp-expl"><textarea id="imp-expl" className="input" rows={2} value={d.explanation} onChange={e => setD({ ...d, explanation: e.target.value })} /></Field>
        </fieldset>
        <details>
          <summary className="field-label" style={{ cursor: 'pointer' }}>Nội dung gốc · {item.source.fileName} · trang {item.source.page ?? '?'} · {item.source.method === 'OCR' ? 'OCR' : 'văn bản'} · tin cậy {Math.round(item.confidence * 100)}%</summary>
          <pre className="imp-raw">{item.rawText}</pre>
        </details>
        {editable && <div className="row">
          <button type="button" className="btn btn-primary" disabled={save.isPending} onClick={() => save.mutate(item.reviewRequired ? { reviewed: true } : {})}>{item.reviewRequired && !item.reviewed ? 'Lưu và đánh dấu đã xem' : 'Lưu'}</button>
          {changed && !item.reviewRequired && <span className="xsmall muted">Có thay đổi chưa lưu</span>}
          {item.excluded
            ? <button type="button" className="btn btn-secondary" disabled={save.isPending} onClick={() => save.mutate({ excluded: false })}>Đưa lại vào danh sách nhập</button>
            : <button type="button" className="btn btn-danger-ghost" disabled={save.isPending} onClick={() => save.mutate({ excluded: true })}>Loại khỏi lần nhập</button>}
        </div>}
      </div>
    </aside>
  )
}

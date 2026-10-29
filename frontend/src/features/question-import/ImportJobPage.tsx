import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, CheckCircle2, Plus, ScanText, Trash2 } from 'lucide-react'
import { importApi, type ImportItem, type ImportResult, type ItemFilter } from './importApi'
import { FILTERS, flagLabel, dirty, draftOf, isOcr, itemState, lowConfidence, mustReview, optionLetter, patchOf, type Draft } from './importModel'
import SubjectCategoryFields from '../question-bank/SubjectCategoryFields'
import { ConfirmDialog } from '../../components/Dialog'
import { Badge, EmptyState, ErrorState, Field, LoadingState, Notice, PageHeader, Spinner } from '../../components/ui'
import { errorStatus, errorText } from '../../lib/errors'
import { DIFFICULTY_LABEL, QUESTION_TYPE_LABEL, formatDateTime } from '../../lib/format'
import './import.css'
import { t, useT, i18nMap } from '../../lib/i18n'
import Select from '../../components/Select'

const KIND_LABEL = i18nMap({ DOCX: 'Word (DOCX)', PDF: 'PDF', IMAGE: 'Ảnh' } as const)

/** Review → edit → validate → import into the Personal bank, as DRAFT or activated right away. Nothing is published. */
export default function ImportJobPage() {
  useT()
  const { id } = useParams()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const [filter, setFilter] = useState<ItemFilter>('ALL')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [result, setResult] = useState<ImportResult | null>(null)
  const [flash, setFlash] = useState<{ tone: 'success' | 'danger' | 'info'; text: string } | null>(null)
  const [discarding, setDiscarding] = useState(false)
  // Reviewed questions usually go straight into exams: activating on import saves one click per question.
  const [activate, setActivate] = useState(true)
  const [resultActive, setResultActive] = useState(false)
  const job = useQuery({ queryKey: ['import-job', id], queryFn: () => importApi.job(id!), refetchInterval: q => q.state.data?.status === 'PROCESSING' ? 1500 : false })
  const ready = job.data?.status === 'READY'
  const items = useQuery({ queryKey: ['import-items', id, filter], queryFn: () => importApi.items(id!, filter, 0, 100), enabled: !!job.data && job.data.status !== 'PROCESSING' })
  const refresh = () => Promise.all([qc.invalidateQueries({ queryKey: ['import-job', id] }), qc.invalidateQueries({ queryKey: ['import-items', id] })])
  const selected = items.data?.content.find(i => i.id === selectedId) ?? null

  const validate = useMutation({ mutationFn: () => importApi.validate(id!), onSuccess: async s => { await refresh(); setFlash({ tone: 'info', text: t('Đã kiểm tra lại: {0}/{1} hợp lệ, {2} cần xem lại, {3} sẵn sàng nhập.', { 0: s.valid, 1: s.total, 2: s.needsReview, 3: s.importable }) }) }, onError: e => setFlash({ tone: 'danger', text: errorText(e) }) })
  const run = useMutation({ mutationFn: () => importApi.importItems(id!, undefined, activate), onSuccess: async r => { setResult(r); setResultActive(activate); setFlash(null); await refresh(); await qc.invalidateQueries({ queryKey: ['questions'] }) }, onError: e => setFlash({ tone: 'danger', text: errorText(e) }) })
  const discard = useMutation({ mutationFn: () => importApi.discard(id!), onSuccess: () => navigate('/questions/imports'), onError: e => { setDiscarding(false); setFlash({ tone: 'danger', text: errorText(e) }) } })

  if (job.isLoading) return <LoadingState rows={5} />
  if (job.isError || !job.data) return <ErrorState error={job.error} onRetry={() => void job.refetch()} />
  const j = job.data, s = j.summary

  return (
    <div className="stack-lg">
      <PageHeader title={j.fileName} crumbs={[{ label: t('Kho cá nhân'), to: '/questions' }, { label: t('Nhập tài liệu'), to: '/questions/imports' }, { label: j.fileName }]}
        description={[KIND_LABEL[j.fileKind], t('{0} trang', { 0: j.pageCount }), j.ocrPageCount ? t('{0} trang nhận dạng ảnh (OCR)', { 0: j.ocrPageCount }) : '', t('tải lên {0}', { 0: formatDateTime(j.createdAt) })].filter(Boolean).join(' · ')}
        actions={ready && <>
          <button type="button" className="btn btn-danger-ghost" onClick={() => setDiscarding(true)}><Trash2 size={16} aria-hidden /> {t('Hủy lô')}</button>
          <button type="button" className="btn btn-secondary" disabled={validate.isPending} onClick={() => validate.mutate()}><CheckCircle2 size={16} aria-hidden /> {t('Kiểm tra lại')}</button>
          <button type="button" className="btn btn-primary" disabled={!s.importable || run.isPending} aria-busy={run.isPending} onClick={() => run.mutate()}><Plus size={16} aria-hidden /> {t('Nhập')} {s.importable} {activate ? t('câu và kích hoạt') : t('câu (bản nháp)')}</button>
          <label className="check small" style={{ alignSelf: 'center' }}><input type="checkbox" checked={activate} onChange={e => setActivate(e.target.checked)} /> {t('Kích hoạt luôn để dùng cho đề thi')}</label>
        </>} />

      {j.status === 'PROCESSING' && <Notice tone="info" live title={t('Đang xử lý tài liệu')}><span className="row" style={{ gap: 8 }}><Spinner size={14} /> {t('Đang đọc chữ và tách câu hỏi. Trang tự cập nhật khi xong.')}</span></Notice>}
      {j.status === 'FAILED' && <Notice tone="danger" title={t('Không xử lý được tài liệu')}>{j.failureReason || t('Tài liệu không đọc được.')} {t('Hãy kiểm tra tệp rồi tải lên lại.')}</Notice>}
      {j.status === 'DISCARDED' && <Notice tone="warning">{t('Lô này đã bị hủy; các câu đã nhập trước đó vẫn giữ trong kho.')}</Notice>}
      {j.warnings.length > 0 && <Notice tone="warning" title={t('Lưu ý khi đọc tài liệu')}><ul style={{ margin: 0, paddingLeft: 18 }}>{j.warnings.map(w => <li key={w} className="small">{w}</li>)}</ul></Notice>}
      {flash && <Notice tone={flash.tone} live>{flash.text}</Notice>}
      {result && <Notice tone="success" live title={resultActive
          ? t('Đã nhập và kích hoạt {0} câu; có thể dùng ngay cho đề thi', { 0: result.imported.length })
          : t('Đã nhập {0} câu vào Kho cá nhân dưới dạng bản nháp', { 0: result.imported.length })}
        action={<Link className="btn btn-secondary btn-sm" to="/questions">{t('Mở Kho cá nhân')}</Link>}>
        {resultActive ? t('Câu đã sẵn sàng trong Kho cá nhân và chưa công khai.') : t('Câu chưa được kích hoạt hay công khai; kích hoạt từng câu khi đã sẵn sàng.')}{result.skipped.length ? t(' {0} câu bỏ qua (chưa hợp lệ, cần xem lại hoặc đã loại).', { 0: result.skipped.length }) : ''}</Notice>}

      {j.status !== 'PROCESSING' && <>
        <div className="tabs" role="tablist" aria-label={t('Lọc câu')}>
          {FILTERS.map(f => <button key={f.value} type="button" role="tab" className="tab" aria-selected={filter === f.value} onClick={() => { setFilter(f.value); setSelectedId(null) }}>{f.label}<span className="count">{f.count(s)}</span></button>)}
        </div>
        <div className="imp-layout" data-panel={selected ? 'open' : 'closed'}>
          <section className="card" aria-label={t('Các câu nhận dạng được')} style={{ overflow: 'hidden' }}>
            {items.isLoading ? <LoadingState rows={4} /> : items.isError ? <ErrorState error={items.error} onRetry={() => void items.refetch()} />
              : !items.data?.content.length ? <EmptyState title={t('Không có câu trong mục này')} />
              : <ol className="imp-list">{items.data.content.map(i => <ItemRow key={i.id} item={i} active={i.id === selectedId} onOpen={() => setSelectedId(i.id)} />)}</ol>}
          </section>
          {selected && <ItemEditor key={selected.id} jobId={j.id} item={selected} editable={ready && !selected.importedQuestionId}
            onClose={() => setSelectedId(null)} onSaved={async () => { await refresh() }} />}
        </div>
      </>}
      {discarding && <ConfirmDialog title={t('Hủy lô nhập này?')} tone="danger" confirmLabel={t('Hủy lô')} busy={discard.isPending}
        description={t('Các câu chưa nhập sẽ không sửa được nữa. Câu đã nhập vào kho vẫn giữ nguyên.')} onConfirm={() => discard.mutate()} onCancel={() => setDiscarding(false)} />}
    </div>
  )
}

function ItemRow({ item, active, onOpen }: { item: ImportItem; active: boolean; onOpen: () => void }) {
  useT()
  const st = itemState(item)
  const attention = mustReview(item)
  return (
    <li>
      <button type="button" className="imp-row" aria-current={active} data-attention={attention || undefined} onClick={onOpen}>
        <span className="row" style={{ gap: 6 }}>
          <strong className="small">{t('Câu')} {item.position + 1}</strong>
          <Badge tone={st.tone}>{st.label}</Badge>
          {isOcr(item) && <Badge tone="warning" icon={<ScanText size={12} aria-hidden />}>OCR</Badge>}
          {lowConfidence(item) && <Badge tone="warning">{t('Tin cậy')} {Math.round(item.confidence * 100)}%</Badge>}
          {item.reviewed && <Badge>{t('Đã xem')}</Badge>}
        </span>
        <span className="small truncate-2" style={{ display: '-webkit-box' }}>{item.content || t('(chưa có nội dung)')}</span>
        <span className="xsmall muted">{QUESTION_TYPE_LABEL[item.questionType]} · {DIFFICULTY_LABEL[item.difficultyLevel]} · trang {item.source.page ?? '?'}{item.errors.length ? t(' · {0} lỗi', { 0: item.errors.length }) : ''}</span>
      </button>
    </li>
  )
}

function ItemEditor({ jobId, item, editable, onClose, onSaved }: { jobId: string; item: ImportItem; editable: boolean; onClose: () => void; onSaved: () => Promise<void> }) {
  useT()
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
      setNotice({ tone: r.valid ? 'success' : 'warning', text: extra.excluded ? t('Đã loại câu này khỏi lần nhập.') : extra.excluded === false ? t('Đã đưa câu trở lại danh sách nhập.') : r.valid ? (r.importable ? t('Đã lưu. Câu sẵn sàng nhập.') : t('Đã lưu. Câu còn cần đánh dấu đã xem lại.')) : t('Đã lưu nhưng câu chưa hợp lệ; xem lỗi bên dưới.') })
    },
    onError: async e => { if (errorStatus(e) === 409) { setNotice({ tone: 'warning', text: t('Câu đã thay đổi ở nơi khác. Đã tải lại; kiểm tra rồi lưu lại.') }); await onSaved() } else setNotice({ tone: 'danger', text: errorText(e) }) },
  })
  const choice = d.questionType === 'MULTIPLE_CHOICE' || d.questionType === 'TRUE_FALSE'
  const setOpt = (n: number, p: Partial<Draft['options'][number]>) => setD(x => ({ ...x, options: x.options.map((o, i) => i === n ? { ...o, ...p } : o) }))
  const changed = dirty(item, d)
  const attention = mustReview(item)

  return (
    <aside className="imp-panel" aria-label={t('Sửa câu {0}', { 0: item.position + 1 })}>
      <div className="imp-panel-head"><h2 style={{ fontSize: '1.15rem' }}>{t('Câu')} {item.position + 1}</h2><button type="button" className="btn btn-ghost btn-sm" onClick={onClose}><ArrowLeft size={14} aria-hidden /> {t('Đóng')}</button></div>
      <div className="imp-panel-body stack">
        {attention && <Notice tone="warning" title={t('Bắt buộc xem lại')}>{isOcr(item) ? t('Câu được nhận dạng từ ảnh (OCR) nên có thể sai chữ. ') : ''}{lowConfidence(item) ? t('Độ tin cậy {0}%. ', { 0: Math.round(item.confidence * 100) }) : ''}{t('So với nội dung gốc bên dưới, sửa nếu cần rồi bấm “Lưu và đánh dấu đã xem”.')}</Notice>}
        {item.importedQuestionId && <Notice tone="success">{t('Đã nhập thành câu bản nháp trong Kho cá nhân.')} <Link to={`/questions?q=${item.importedQuestionId}`}>{t('Mở câu hỏi')}</Link></Notice>}
        {notice && <Notice tone={notice.tone} live>{notice.text}</Notice>}
        {item.flags.length > 0 && <div className="row" style={{ gap: 6 }}>{item.flags.map(f => <Badge key={f} tone="warning">{flagLabel(f)}</Badge>)}</div>}
        {item.errors.length > 0 && <ul className="imp-errors">{item.errors.map(e => <li key={e.code} className="field-error">{e.message}</li>)}</ul>}
        <fieldset className="stack" disabled={!editable || save.isPending} style={{ border: 0, padding: 0, margin: 0 }}>
          <Field label={t('Nội dung câu hỏi')} htmlFor="imp-content" required><textarea id="imp-content" className="input" rows={4} value={d.content} onChange={e => setD({ ...d, content: e.target.value })} /></Field>
          <div className="grid-2">
            <Field label={t('Loại')} htmlFor="imp-type"><Select id="imp-type" className="input" value={d.questionType} onChange={e => setD({ ...d, questionType: e.target.value as Draft['questionType'] })}>{Object.entries(QUESTION_TYPE_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}</Select></Field>
            <Field label={t('Độ khó')} htmlFor="imp-diff"><Select id="imp-diff" className="input" value={d.difficultyLevel} onChange={e => setD({ ...d, difficultyLevel: e.target.value as Draft['difficultyLevel'] })}>{Object.entries(DIFFICULTY_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}</Select></Field>
          </div>
          <div className="grid-2"><SubjectCategoryFields subjectId={subjectId} categoryId={d.categoryId} onSubject={setSubjectId} onCategory={id => setD(x => ({ ...x, categoryId: id }))} required /></div>
          {choice && <fieldset className="stack-sm" style={{ border: 0, padding: 0, margin: 0 }}>
            <legend className="field-label">{t('Phương án (đánh dấu đáp án đúng)')}</legend>
            {d.options.map((o, n) => <div key={n} className="row imp-option" style={{ flexWrap: 'nowrap', gap: 8 }}>
              <span className="option-key" aria-hidden>{optionLetter(n)}</span>
              <input className="input" aria-label={t('Phương án {0}', { 0: optionLetter(n) })} value={o.content} onChange={e => setOpt(n, { content: e.target.value })} />
              <label className="check small" style={{ whiteSpace: 'nowrap' }}><input type="checkbox" checked={o.correct} onChange={e => setOpt(n, { correct: e.target.checked })} /> {t('Đúng')}</label>
              <button type="button" className="btn btn-ghost btn-icon btn-sm" aria-label={t('Bỏ phương án {0}', { 0: optionLetter(n) })} onClick={() => setD(x => ({ ...x, options: x.options.filter((_, i) => i !== n) }))}><Trash2 size={14} /></button>
            </div>)}
            {d.options.length < 8 && <button type="button" className="btn btn-ghost btn-sm" style={{ alignSelf: 'flex-start' }} onClick={() => setD(x => ({ ...x, options: [...x.options, { content: '', correct: false, displayOrder: x.options.length }] }))}><Plus size={14} aria-hidden /> {t('Thêm phương án')}</button>}
            {!d.options.some(o => o.correct) && <p className="field-error">{t('Chưa có đáp án đúng. Hệ thống không tự đoán; hãy chọn đáp án.')}</p>}
          </fieldset>}
          {!choice && <Field label={d.questionType === 'ESSAY' ? t('Hướng dẫn chấm') : t('Đáp án chuẩn')} htmlFor="imp-answer"><textarea id="imp-answer" className="input" rows={2} value={d.expectedAnswer} onChange={e => setD({ ...d, expectedAnswer: e.target.value })} /></Field>}
          <Field label={t('Giải thích')} htmlFor="imp-expl"><textarea id="imp-expl" className="input" rows={2} value={d.explanation} onChange={e => setD({ ...d, explanation: e.target.value })} /></Field>
        </fieldset>
        <details>
          <summary className="field-label" style={{ cursor: 'pointer' }}>{t('Nội dung gốc ·')} {item.source.fileName} · trang {item.source.page ?? '?'} · {item.source.method === 'OCR' ? 'OCR' : t('văn bản')} {t('· tin cậy')} {Math.round(item.confidence * 100)}%</summary>
          <pre className="imp-raw">{item.rawText}</pre>
        </details>
        {editable && <div className="row">
          <button type="button" className="btn btn-primary" disabled={save.isPending} onClick={() => save.mutate(item.reviewRequired ? { reviewed: true } : {})}>{item.reviewRequired && !item.reviewed ? t('Lưu và đánh dấu đã xem') : t('Lưu')}</button>
          {changed && !item.reviewRequired && <span className="xsmall muted">{t('Có thay đổi chưa lưu')}</span>}
          {item.excluded
            ? <button type="button" className="btn btn-secondary" disabled={save.isPending} onClick={() => save.mutate({ excluded: false })}>{t('Đưa lại vào danh sách nhập')}</button>
            : <button type="button" className="btn btn-danger-ghost" disabled={save.isPending} onClick={() => save.mutate({ excluded: true })}>{t('Loại khỏi lần nhập')}</button>}
        </div>}
      </div>
    </aside>
  )
}

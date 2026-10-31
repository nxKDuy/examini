import { useEffect, useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Plus, RefreshCw, Shuffle, Trash2, X } from 'lucide-react'
import { examApi } from './examApi'
import type { Question } from '../question-bank/questionApi'
import { bankApi } from '../banks/bankApi'
import { BANK_TYPE_LABEL } from '../banks/bankModel'
import SubjectCategoryFields from '../question-bank/SubjectCategoryFields'
import { blueprintError, blueprintRequest, chunk, pinsFrom, removeFromRow, rowError, type BlueprintPreview, type BlueprintRowCfg } from './builderModel'
import { Dialog } from '../../components/Dialog'
import { Badge, Notice } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { DIFFICULTY_LABEL, QUESTION_TYPE_LABEL, formatScore } from '../../lib/format'
import { t, useT } from '../../lib/i18n'
import Select from '../../components/Select'

let rowSeq = 0
const newRow = (): BlueprintRowCfg => ({ key: `row-${++rowSeq}`, count: 5, pointsPerQuestion: 1 })

/**
 * Exam blueprint (scope lock B): rows of filters + count + points → server preview with per-row shortfall,
 * then regenerate all, one row, swap or remove single questions. Nothing is saved until the editor saves.
 */
export default function BlueprintDialog({ inExam, onApply, onClose, initialRows, title }: {
  inExam: string[]; onApply: (items: { question: Question; weight: number }[]) => void; onClose: () => void
  /** Rows prepared elsewhere (the exam matrix): previewed right away. */
  initialRows?: (BlueprintRowCfg & { label?: string })[]; title?: string
}) {
  useT()
  const [rows, setRows] = useState<(BlueprintRowCfg & { label?: string })[]>(() => initialRows?.length ? initialRows : [newRow()])
  const [shuffle, setShuffle] = useState(false)
  const [preview, setPreview] = useState<BlueprintPreview | null>(null)
  const [excluded, setExcluded] = useState<string[]>([])
  const [busy, setBusy] = useState(false)
  const [failure, setFailure] = useState('')
  const problem = blueprintError(rows, inExam.length)
  const stale = !!preview && preview.rows.length !== rows.length

  async function run(nextRows: BlueprintRowCfg[], pins: Record<number, string[]> = {}, extraExclude: string[] = []) {
    const exclude = [...inExam, ...excluded, ...extraExclude]
    setBusy(true); setFailure('')
    try {
      const p = await examApi.blueprintPreview(blueprintRequest(nextRows, shuffle, pins, exclude))
      setRows(nextRows); setPreview(p)
      if (extraExclude.length) setExcluded(prev => [...new Set([...prev, ...extraExclude])])
    } catch (e) { setFailure(errorText(e)) } finally { setBusy(false) }
  }

  async function apply() {
    if (!preview) return
    setBusy(true); setFailure('')
    try {
      // Full question data for the editor list; the server re-checks eligibility again on save.
      const ids = preview.questions.map(q => q.questionId)
      const full = new Map((await Promise.all(chunk(ids).map(c => bankApi.eligibleBatch(c)))).flat().map(q => [q.id, q]))
      onApply(preview.questions.map(q => ({ question: full.get(q.questionId) ?? ({ id: q.questionId, content: q.content, questionType: q.questionType, questionVersion: q.questionVersion } as Question), weight: q.points })))
    } catch (e) { setFailure(errorText(e)); setBusy(false) }
  }

  const started = useRef(false)
  useEffect(() => {
    if (started.current || !initialRows?.length) return
    started.current = true
    void run(initialRows)
    // eslint-disable-next-line react-hooks/exhaustive-deps -- preview the prepared rows once, on open
  }, [])

  const patch = (i: number, p: Partial<BlueprintRowCfg>) => setRows(list => list.map((r, n) => n === i ? { ...r, ...p } : r))

  return (
    <Dialog size="lg" title={title ?? t('Tạo đề theo khung')} onClose={onClose} busy={busy}
      description={t('Mỗi dòng chọn ngẫu nhiên trong đúng bộ lọc của dòng đó. Thiếu câu thì báo thiếu, không lấy bù từ dòng khác. Chưa lưu gì cho đến khi bạn thêm vào đề và bấm Lưu.')}
      footer={<>
        {preview && <span className="small muted" style={{ marginRight: 'auto' }} role="status">{t('{n}/{requested} câu · {points} điểm', { n: preview.totalQuestions, requested: preview.requestedQuestions, points: formatScore(preview.totalPoints) })}</span>}
        <button type="button" className="btn btn-secondary" onClick={onClose} disabled={busy}>{t('Hủy')}</button>
        <button type="button" className="btn btn-secondary" disabled={!!problem || busy} onClick={() => void run(rows)}><RefreshCw size={15} aria-hidden /> {t(preview ? 'Tạo lại toàn bộ' : 'Xem trước')}</button>
        <button type="button" className="btn btn-primary" disabled={!preview || !preview.totalQuestions || stale || busy} onClick={() => void apply()}>{t('Thêm {n} câu vào đề', { n: preview?.totalQuestions ?? '' })}</button>
      </>}>
      <div className="stack">
        {initialRows?.length ? <p className="small muted">{t('{n} ô của ma trận, mỗi ô là một dòng bên dưới. Muốn đổi số câu, đóng lại và sửa ma trận.', { n: rows.length })}</p>
        : <section className="stack-sm" aria-label={t('Khung đề')}>
          {rows.map((r, i) => <BlueprintRowEditor key={r.key} index={i} row={r} onChange={p => patch(i, p)} canRemove={rows.length > 1}
            onRemove={() => { setRows(list => list.filter((_, n) => n !== i)); setPreview(null) }} />)}
          <div className="row">
            <button type="button" className="btn btn-ghost btn-sm" disabled={rows.length >= 50} onClick={() => setRows(list => [...list, newRow()])}><Plus size={14} aria-hidden /> {t('Thêm dòng')}</button>
            <label className="check small" style={{ marginLeft: 'auto' }}><input type="checkbox" checked={shuffle} onChange={e => setShuffle(e.target.checked)} /> <span><Shuffle size={13} aria-hidden /> {t('Trộn thứ tự câu')}</span></label>
          </div>
          {problem && <p className="field-error">{problem}</p>}
          {inExam.length > 0 && <p className="xsmall muted">{t('{n} câu đã có trong đề sẽ không bị chọn lại.', { n: inExam.length })}</p>}
        </section>}
        {failure && <Notice tone="danger" live>{failure}</Notice>}
        {stale && <Notice tone="warning">{t('Khung đề đã đổi số dòng. Bấm “Tạo lại toàn bộ” để xem trước theo khung mới.')}</Notice>}
        {preview && !stale && <section className="stack" aria-label={t('Bản xem trước')}>
          <Notice tone={preview.complete ? 'success' : 'warning'} live title={preview.complete ? t('Đủ câu theo khung') : t('Thiếu {n} câu', { n: preview.totalShortfall })}>
            {t('{n}/{requested} câu · tổng {points} điểm', { n: preview.totalQuestions, requested: preview.requestedQuestions, points: formatScore(preview.totalPoints) })}{t(preview.shuffle ? ' · thứ tự đã trộn' : ' · theo thứ tự dòng')}.
            {!preview.complete && t(' Có thể nới bộ lọc của dòng thiếu, giảm số câu, hoặc vẫn dùng bản nháp này.')}
          </Notice>
          {preview.rows.map(row => <article key={row.rowIndex} className="bp-row" data-short={row.shortfall > 0 || undefined}>
            <header className="row" style={{ justifyContent: 'space-between' }}>
              <strong className="small">{t('Dòng {n}', { n: row.rowIndex + 1 })}: {rows[row.rowIndex]?.label ? `${rows[row.rowIndex].label} · ${t('{points} điểm/câu', { points: formatScore(row.pointsPerQuestion) })}` : rowLabel(row)}</strong>
              <span className="row" style={{ gap: 8 }}>
                {row.shortfall > 0 ? <Badge tone="warning">{t('Thiếu {n} · có {available}', { n: row.shortfall, available: row.available })}</Badge> : <Badge>{t('{n}/{requested} câu', { n: row.picked, requested: row.requested })}</Badge>}
                <button type="button" className="btn btn-ghost btn-sm" disabled={busy} onClick={() => void run(rows, pinsFrom(preview, { skipRow: row.rowIndex }))}><RefreshCw size={13} aria-hidden /> {t('Tạo lại dòng')}</button>
              </span>
            </header>
            {row.droppedPinnedIds.length > 0 && <p className="xsmall muted">{t('{n} câu giữ lại không còn đủ điều kiện nên đã được thay.', { n: row.droppedPinnedIds.length })}</p>}
            <ol className="bp-questions">
              {row.questions.map(q => <li key={q.questionId}>
                <span className="small truncate-2" style={{ flex: 1, minWidth: 0 }}>{q.content}<span className="xsmall muted" style={{ display: 'block' }}>{t(QUESTION_TYPE_LABEL[q.questionType] ?? q.questionType)} · v{q.questionVersion} · {t('{points} điểm', { points: formatScore(q.points) })}</span></span>
                <button type="button" className="btn btn-ghost btn-sm" disabled={busy} onClick={() => void run(rows, pinsFrom(preview, { dropId: q.questionId }), [q.questionId])}><Shuffle size={13} aria-hidden /> {t('Đổi câu')}</button>
                <button type="button" className="btn btn-ghost btn-icon btn-sm" aria-label={t('Bỏ câu này')} disabled={busy} onClick={() => { const r = removeFromRow(rows, preview, row.rowIndex, q.questionId); void run(r.rows, r.pins, [q.questionId]) }}><X size={14} /></button>
              </li>)}
            </ol>
          </article>)}
        </section>}
      </div>
    </Dialog>
  )
}

function rowLabel(r: { categoryId: string | null; questionType: string | null; difficulty: string | null; pointsPerQuestion: number }) {
  return [t(r.questionType ? QUESTION_TYPE_LABEL[r.questionType] : 'Mọi loại'), t(r.difficulty ? DIFFICULTY_LABEL[r.difficulty] : 'mọi độ khó'), r.categoryId ? t('theo danh mục') : '', t('{points} điểm/câu', { points: formatScore(r.pointsPerQuestion) })].filter(Boolean).join(' · ')
}

function BlueprintRowEditor({ index, row, onChange, onRemove, canRemove }: { index: number; row: BlueprintRowCfg; onChange: (p: Partial<BlueprintRowCfg>) => void; onRemove: () => void; canRemove: boolean }) {
  useT()
  const [subjectId, setSubjectId] = useState('')
  const banks = useQuery({ queryKey: ['banks', 'picker'], queryFn: bankApi.allBanks })
  const err = rowError(row)
  return (
    <fieldset className="bp-edit">
      <legend className="field-label">{t('Dòng {n}', { n: index + 1 })}</legend>
      <div className="bp-grid">
        <div className="field"><label className="field-label" htmlFor={`bp-bank-${row.key}`}>{t('Ngân hàng')}</label>
          <Select id={`bp-bank-${row.key}`} className="input" value={row.bankId ?? ''} onChange={e => onChange({ bankId: e.target.value || undefined })}>
            <option value="">{t('Tất cả ngân hàng dùng được')}</option>
            {(banks.data ?? []).map(b => <option key={b.id} value={b.id}>{BANK_TYPE_LABEL[b.type]} · {b.name}</option>)}
          </Select></div>
        <SubjectCategoryFields subjectId={subjectId} categoryId={row.categoryId ?? ''} allowAll
          onSubject={id => { setSubjectId(id); onChange({ categoryId: undefined }) }} onCategory={id => onChange({ categoryId: id || undefined })} />
        <div className="field"><label className="field-label" htmlFor={`bp-type-${row.key}`}>{t('Loại')}</label>
          <Select id={`bp-type-${row.key}`} className="input" value={row.questionType ?? ''} onChange={e => onChange({ questionType: e.target.value || undefined })}>
            <option value="">{t('Mọi loại')}</option>{Object.entries(QUESTION_TYPE_LABEL).map(([v, l]) => <option key={v} value={v}>{t(l)}</option>)}
          </Select></div>
        <div className="field"><label className="field-label" htmlFor={`bp-diff-${row.key}`}>{t('Độ khó')}</label>
          <Select id={`bp-diff-${row.key}`} className="input" value={row.difficulty ?? ''} onChange={e => onChange({ difficulty: e.target.value || undefined })}>
            <option value="">{t('Mọi độ khó')}</option>{Object.entries(DIFFICULTY_LABEL).map(([v, l]) => <option key={v} value={v}>{t(l)}</option>)}
          </Select></div>
        <div className="field"><label className="field-label" htmlFor={`bp-count-${row.key}`}>{t('Số câu')}</label>
          <input id={`bp-count-${row.key}`} type="number" min={1} max={200} step={1} className="input" value={Number.isFinite(row.count) ? row.count : ''} onChange={e => onChange({ count: Math.floor(Number(e.target.value)) })} /></div>
        <div className="field"><label className="field-label" htmlFor={`bp-pts-${row.key}`}>{t('Điểm mỗi câu')}</label>
          <input id={`bp-pts-${row.key}`} type="number" min={0.25} step={0.25} className="input" value={Number.isFinite(row.pointsPerQuestion) ? row.pointsPerQuestion : ''} onChange={e => onChange({ pointsPerQuestion: Number(e.target.value) })} /></div>
      </div>
      <div className="row" style={{ justifyContent: 'space-between' }}>
        {err ? <span className="field-error">{err}</span> : <span className="xsmall muted">{t('{n} câu × {each} = {total} điểm', { n: row.count, each: formatScore(row.pointsPerQuestion), total: formatScore(row.count * row.pointsPerQuestion) })}</span>}
        {canRemove && <button type="button" className="btn btn-danger-ghost btn-sm" onClick={onRemove}><Trash2 size={13} aria-hidden /> {t('Bỏ dòng')}</button>}
      </div>
    </fieldset>
  )
}

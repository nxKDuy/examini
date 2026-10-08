import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowLeft, Copy, PenLine } from 'lucide-react'
import { gradingApi, type GradeItem, type ResultPolicy, type SubmissionDetail } from './gradingApi'
import {
  canEdit, canRegrade, canRelease, formFromDetail, isAutoGraded, isChoiceAnswer, isDirty, isInvalidated, isStaleRevision, itemProblems, previewTotal, publicationOf, scoreDelta, shortStudent,
  toDraftRequest, unresolvedItems, type GradeForm,
} from './gradingModel'
import { finalizeWithDraft, type DraftSavedError } from './gradingFlow'
import { PublicationBadge } from './SubmissionQueue'
import { isInvalidatedConflict } from '../participants/participantModel'
import { ResultStatusBadge } from '../../components/status'
import { ConfirmDialog, Dialog } from '../../components/Dialog'
import { Badge, ErrorState, Field, LoadingState, Notice } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { QUESTION_TYPE_LABEL, formatDateTime, formatDuration, formatScore } from '../../lib/format'

interface Loaded { rev: number; initial: GradeForm; form: GradeForm }
const make = (d: SubmissionDetail): Loaded => { const f = formFromDetail(d); return { rev: d.resultRevision, initial: f, form: structuredClone(f) } }
type Flash = { tone: 'success' | 'danger'; text: string }
type Confirm = 'finalize' | 'release' | 'regrade' | 'reload' | null

/** One submission: frozen answers with per-item scores (middle) and the score/feedback/publication panel (right). Nothing here decides eligibility or totals. */
export default function SubmissionWorkspace({ id, policy, narrow, onDirty, onBack }: {
  id: string; policy: ResultPolicy | undefined; narrow: boolean; onDirty: (dirty: boolean) => void; onBack: () => void
}) {
  const qc = useQueryClient()
  const detail = useQuery({ queryKey: ['grading', 'detail', id], queryFn: () => gradingApi.detail(id), refetchOnWindowFocus: false })
  const d = detail.data
  const [loaded, setLoaded] = useState<Loaded | null>(null)
  const loadedRef = useRef<Loaded | null>(null)
  const [stale, setStale] = useState(false)
  const [flash, setFlash] = useState<Flash | null>(null)
  const [confirm, setConfirm] = useState<Confirm>(null)
  const [regrade, setRegrade] = useState({ publicReason: '', privateAuditNote: '' })
  const headingRef = useRef<HTMLHeadingElement>(null)

  const put = useCallback((l: Loaded | null) => { loadedRef.current = l; setLoaded(l) }, [])
  // New data replaces the form only while there is nothing unsaved; otherwise the Teacher's edits stay and the page says it is stale.
  useEffect(() => {
    if (!d) return
    const cur = loadedRef.current
    if (!cur) put(make(d))
    else if (cur.rev !== d.resultRevision) { if (isDirty(cur.form, cur.initial)) setStale(true); else put(make(d)) }
  }, [d, put])
  const dirty = !!loaded && isDirty(loaded.form, loaded.initial)
  useEffect(() => { onDirty(dirty); return () => onDirty(false) }, [dirty, onDirty])
  useEffect(() => {
    if (!dirty) return
    const warn = (e: BeforeUnloadEvent) => { e.preventDefault() }
    window.addEventListener('beforeunload', warn); return () => window.removeEventListener('beforeunload', warn)
  }, [dirty])
  useEffect(() => { headingRef.current?.focus() }, [id])

  const problems = useMemo(() => (d && loaded ? itemProblems(d, loaded.form, loaded.initial) : {}), [d, loaded])
  const invalid = Object.keys(problems).length > 0
  const unresolved = useMemo(() => (d && loaded ? unresolvedItems(d, loaded.form) : []), [d, loaded])

  const refreshLists = () => Promise.all([qc.invalidateQueries({ queryKey: ['grading', 'list'] }), qc.invalidateQueries({ queryKey: ['grading', 'overview'] })])
  const adopt = useCallback(async (next: SubmissionDetail | undefined) => {
    const fresh = next?.items ? next : (await detail.refetch()).data
    if (fresh) { qc.setQueryData(['grading', 'detail', id], fresh); put(make(fresh)) }
    setStale(false); await refreshLists()
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id, put, qc, detail])
  const fail = (e: unknown, prefix = '') => {
    setConfirm(null)
    if (isInvalidatedConflict(e)) { setFlash({ tone: 'danger', text: prefix + errorText(e) }); void detail.refetch() }
    else if (isStaleRevision(e)) { setStale(true); setFlash(prefix ? { tone: 'danger', text: `${prefix}Bài đã thay đổi ở nơi khác, hãy tải lại bản mới.` } : null) }
    else setFlash({ tone: 'danger', text: prefix + errorText(e) })
  }

  const save = useMutation({
    mutationFn: () => gradingApi.saveDraft(id, toDraftRequest(d!, loaded!.form, loaded!.initial)),
    onSuccess: async data => { await adopt(data); setFlash({ tone: 'success', text: 'Đã lưu bản nháp. Học sinh chưa thấy gì thay đổi.' }) }, onError: e => fail(e),
  })
  const finalize = useMutation({
    // The current draft is saved first so the revision being finalized is exactly what is on screen; once saved it is adopted,
    // so a failed finalize leaves the saved revision as the baseline and a retry cannot reuse the old one.
    mutationFn: () => finalizeWithDraft({
      dirty, currentRevision: d!.resultRevision,
      saveDraft: () => gradingApi.saveDraft(id, toDraftRequest(d!, loaded!.form, loaded!.initial)),
      adopt: saved => adopt(saved), finalize: rev => gradingApi.finalize(id, rev),
    }),
    onSuccess: async data => { setConfirm(null); await adopt(data); setFlash({ tone: 'success', text: policy?.autoRelease === false ? 'Đã hoàn tất chấm. Học sinh chưa thấy cho tới khi bạn Công bố.' : 'Đã hoàn tất chấm và công bố cho học sinh.' }) },
    onError: e => fail(e, (e as DraftSavedError)?.draftSaved ? 'Đã lưu bản nháp nhưng chưa hoàn tất chấm. ' : ''),
  })
  const release = useMutation({
    mutationFn: () => gradingApi.release(id, d!.resultRevision),
    onSuccess: async data => { setConfirm(null); await adopt(data); setFlash({ tone: 'success', text: 'Đã công bố kết quả cho học sinh.' }) }, onError: e => fail(e),
  })
  const regradeM = useMutation({
    mutationFn: () => gradingApi.regrade(id, { expectedRevision: d!.resultRevision, publicReason: regrade.publicReason.trim(), ...(regrade.privateAuditNote.trim() ? { privateAuditNote: regrade.privateAuditNote.trim() } : {}) }),
    onSuccess: async data => { setConfirm(null); setRegrade({ publicReason: '', privateAuditNote: '' }); await adopt(data); setFlash({ tone: 'success', text: 'Đã mở chấm lại. Học sinh vẫn thấy bản đã công bố trước đó cho tới khi bạn Công bố bản mới.' }) }, onError: e => fail(e),
  })
  const busy = save.isPending || finalize.isPending || release.isPending || regradeM.isPending

  if (detail.isLoading || (d && !loaded)) return <div className="card card-body" style={{ gridColumn: 'span 2' }}><LoadingState rows={5} /></div>
  if (detail.isError || !d || !loaded) return <div className="card" style={{ gridColumn: 'span 2' }}><ErrorState error={detail.error} onRetry={() => void detail.refetch()} /></div>

  const { form } = loaded
  const attemptInvalid = isInvalidated(d)
  const editable = canEdit(d)
  const pub = publicationOf(d)
  const total = previewTotal(d, form)
  const delta = scoreDelta(editable ? total : d.score, d.publishedScore)
  const setItem = (qid: string, patch: Partial<GradeForm['items'][string]>) => put({ ...loaded, form: { ...form, items: { ...form.items, [qid]: { ...form.items[qid], ...patch } } } })
  const jump = (qid: string) => { const el = document.getElementById(`item-${qid}`); el?.scrollIntoView({ block: 'center' }); el?.querySelector<HTMLElement>('input,textarea')?.focus() }
  const reasonOk = regrade.publicReason.trim().length > 0
  const finalizeBlock = !editable ? null : stale ? 'Bài đã thay đổi, tải lại bản mới trước.' : invalid ? 'Sửa các điểm chưa hợp lệ.' : unresolved.length ? `Còn ${unresolved.length} câu cần chấm tay.` : null

  return (
    <>
      <section className="stack" aria-label="Bài làm của thí sinh" style={{ minWidth: 0 }}>
        <div className="card card-body stack-sm">
          {narrow && <div><button type="button" className="btn btn-secondary btn-sm" onClick={onBack}><ArrowLeft size={14} aria-hidden /> Danh sách bài nộp</button></div>}
          <div className="spread">
            <h2 ref={headingRef} tabIndex={-1} style={{ outline: 'none' }}>Thí sinh <span className="grade-id" title={d.studentId}>{shortStudent(d.studentId)}</span> · Lần #{d.attemptNumber}</h2>
            <span className="row"><ResultStatusBadge status={d.status} /><PublicationBadge s={d} /></span>
          </div>
          <p className="xsmall muted row" style={{ gap: 6 }}>
            Mã đầy đủ <span className="grade-id">{d.studentId}</span>
            <button type="button" className="btn btn-ghost btn-sm" aria-label="Sao chép mã thí sinh" onClick={() => { void navigator.clipboard?.writeText(d.studentId).catch(() => undefined) }}><Copy size={12} aria-hidden /></button>
            · Nộp {formatDateTime(d.submittedAt, '-')}{d.durationSeconds ? ` · Làm trong ${formatDuration(d.durationSeconds)}` : ''}
          </p>
          {d.items.some(i => i.needsReview) && (
            <div className="grade-jump" aria-label="Câu cần chấm tay">
              <span className="small">Câu cần chấm tay:</span>
              {d.items.filter(i => i.needsReview).map(i => {
                const open = unresolved.some(u => u.questionId === i.questionId)
                return <button key={i.questionId} type="button" data-open={open} onClick={() => jump(i.questionId)} aria-label={`Câu ${i.position}${open ? ', chưa chấm' : ', đã chấm'}`}>{i.position}</button>
              })}
            </div>
          )}
        </div>
        {stale && (
          <Notice tone="warning" live title="Bài này đã được cập nhật ở nơi khác"
            action={<button type="button" className="btn btn-secondary btn-sm" onClick={() => setConfirm('reload')}>Tải lại bản mới</button>}>
            Thay đổi của bạn chưa được gửi và chưa bị mất. Tải lại sẽ thay bằng bản mới nhất; hãy sao chép phần bạn cần giữ trước.
          </Notice>
        )}
        {attemptInvalid && <Notice tone="danger" title="Lượt làm bài này đã bị vô hiệu hóa">Lý do công khai: {d.invalidationReason?.trim() || 'không ghi'}. Kết quả và nhật ký được giữ lại nhưng không thể chấm, công bố hay chấm lại, và bài bị loại khỏi kết quả hợp lệ. Muốn cho thi lại, hãy cấp thêm lượt ở tab Tham gia của kỳ thi.</Notice>}
        {!editable && !attemptInvalid && <Notice tone="info">Bài đã hoàn tất chấm nên chỉ xem. Muốn sửa điểm, dùng Chấm lại ở khung bên cạnh (bắt buộc có lý do).</Notice>}
        <ol className="stack" style={{ listStyle: 'none', padding: 0, margin: 0 }}>
          {d.items.map(i => (
            <ItemCard key={i.questionId} item={i} form={form.items[i.questionId]} problem={problems[i.questionId]} editable={editable && !busy}
              initial={loaded.initial.items[i.questionId]} onChange={patch => setItem(i.questionId, patch)} />
          ))}
        </ol>
      </section>

      <aside className="card card-body stack grade-panel" aria-label="Điểm và nhận xét">
        {flash && <Notice tone={flash.tone} live>{flash.text}</Notice>}
        <div className="grade-compare">
          <div>
            <div className="xsmall muted">Học sinh đang thấy</div>
            {pub === 'NONE' ? <p className="small">Chưa công bố. Học sinh chưa thấy điểm.</p>
              : <><div className="big">{formatScore(d.publishedScore)}<span className="muted small"> / {formatScore(d.totalScore)}</span></div>
                  <div className="xsmall muted">Bản công bố #{d.publishedRevision}{pub === 'OUTDATED' ? ' (cũ, bản mới chưa công bố)' : ''}</div></>}
          </div>
          <div>
            <div className="xsmall muted">{editable ? 'Bản nháp hiện tại' : 'Bản đã chấm'} #{d.resultRevision}</div>
            <div className="big">{formatScore(editable ? total : d.score)}<span className="muted small"> / {formatScore(d.totalScore)}</span></div>
            {delta !== null && delta !== 0 && <div className="xsmall">So với bản học sinh thấy: <strong>{delta > 0 ? '+' : ''}{formatScore(delta)}</strong></div>}
            {editable && <div className="xsmall muted">Tạm tính theo ô điểm; máy chủ tính tổng khi lưu. Còn {d.pendingManualItemCount} câu chờ chấm tay.</div>}
            {d.gradedAt && <div className="xsmall muted">Chấm xong {formatDateTime(d.gradedAt, '-')}</div>}
          </div>
        </div>
        <Field label="Nhận xét chung (học sinh thấy khi công bố)" htmlFor="overall-feedback">
          <textarea id="overall-feedback" className="input" rows={4} value={form.feedback} disabled={!editable || busy} onChange={e => put({ ...loaded, form: { ...form, feedback: e.target.value } })} />
        </Field>
        <div className="grade-actions">
          {finalizeBlock && <p className="xsmall muted" role="status">{finalizeBlock}</p>}
          {dirty && editable && <p className="xsmall" role="status"><PenLine size={12} aria-hidden /> Có thay đổi chưa lưu.</p>}
          {editable && <>
            <button type="button" className="btn btn-secondary" disabled={!dirty || invalid || stale || busy} onClick={() => save.mutate()}>{save.isPending ? 'Đang lưu…' : 'Lưu nháp'}</button>
            <button type="button" className="btn btn-primary" disabled={!!finalizeBlock || busy} onClick={() => setConfirm('finalize')}>Hoàn tất chấm</button>
            {policy && <p className="xsmall muted">{policy.autoRelease ? 'Kỳ thi tự công bố ngay khi hoàn tất chấm.' : 'Kỳ thi công bố riêng: sau khi hoàn tất, bấm Công bố để học sinh thấy.'}</p>}
          </>}
          {canRelease(d) && <button type="button" className="btn btn-primary" disabled={stale || busy} onClick={() => setConfirm('release')}>Công bố cho học sinh</button>}
          {canRegrade(d) && <button type="button" className="btn btn-secondary" disabled={stale || busy} onClick={() => setConfirm('regrade')}>Chấm lại</button>}
        </div>
      </aside>

      {confirm === 'finalize' && (
        <ConfirmDialog title="Hoàn tất chấm bài này?" confirmLabel="Hoàn tất chấm" busy={finalize.isPending} onConfirm={() => finalize.mutate()} onCancel={() => setConfirm(null)}
          description={`${dirty ? 'Bản nháp hiện tại sẽ được lưu trước. ' : ''}Tổng điểm ${formatScore(total)}/${formatScore(d.totalScore)} (máy chủ tính lại). ${policy?.autoRelease === false ? 'Học sinh chưa thấy cho tới khi bạn Công bố.' : 'Kết quả sẽ được công bố cho học sinh ngay.'} Sau đó chỉ sửa được bằng Chấm lại.`} />
      )}
      {confirm === 'release' && (
        <ConfirmDialog title="Công bố kết quả?" confirmLabel="Công bố" busy={release.isPending} onConfirm={() => release.mutate()} onCancel={() => setConfirm(null)}
          description={`Học sinh sẽ thấy bản #${d.resultRevision}: ${formatScore(d.score)}/${formatScore(d.totalScore)} (theo các mục kỳ thi cho phép hiển thị) và nhận thông báo.`} />
      )}
      {confirm === 'reload' && (
        <ConfirmDialog title="Tải lại bản mới nhất?" tone="danger" confirmLabel="Tải lại, bỏ thay đổi" onCancel={() => setConfirm(null)}
          description={dirty ? 'Các thay đổi chưa lưu trên màn hình sẽ bị thay bằng bản mới nhất.' : 'Bài sẽ được tải lại.'}
          onConfirm={() => { setConfirm(null); void adopt(undefined) }} />
      )}
      {confirm === 'regrade' && (
        <Dialog title="Chấm lại bài này" description="Bản đã công bố vẫn giữ nguyên cho tới khi bạn Công bố bản mới." onClose={() => setConfirm(null)} busy={regradeM.isPending}
          footer={<>
            <button type="button" className="btn btn-secondary" onClick={() => setConfirm(null)} disabled={regradeM.isPending}>Hủy</button>
            <button type="button" className="btn btn-primary" disabled={!reasonOk || regradeM.isPending} onClick={() => regradeM.mutate()}>{regradeM.isPending ? 'Đang xử lý…' : 'Bắt đầu chấm lại'}</button>
          </>}>
          <div className="stack">
            <Field label="Lý do công khai" htmlFor="regrade-public" required hint="Học sinh sẽ thấy lý do này trong lịch sử công bố.">
              <textarea id="regrade-public" className="input" rows={3} value={regrade.publicReason} onChange={e => setRegrade({ ...regrade, publicReason: e.target.value })} data-autofocus />
            </Field>
            <Field label="Ghi chú nội bộ (tùy chọn)" htmlFor="regrade-private" hint="Chỉ giáo viên thấy. Không hiện cho học sinh.">
              <textarea id="regrade-private" className="input" rows={2} value={regrade.privateAuditNote} onChange={e => setRegrade({ ...regrade, privateAuditNote: e.target.value })} />
            </Field>
          </div>
        </Dialog>
      )}
    </>
  )
}

function ItemCard({ item, form, initial, problem, editable, onChange }: {
  item: GradeItem; form: GradeForm['items'][string]; initial: GradeForm['items'][string]
  problem?: { score?: string; reason?: string }; editable: boolean; onChange: (patch: Partial<GradeForm['items'][string]>) => void
}) {
  const response = isChoiceAnswer(item.questionType) ? item.selectedOptionContent : item.answerContent
  const showReason = isAutoGraded(item.questionType) && form.score.trim() !== '' && form.score.trim() !== initial.score.trim() && Number(form.score) !== item.autoScore
  const fid = `item-${item.questionId}`
  return (
    <li id={fid} className="grade-item" data-review={item.needsReview}>
      <div className="spread">
        <span className="small">Câu {item.position} · {QUESTION_TYPE_LABEL[item.questionType]} · phiên bản {item.questionVersion}</span>
        <span className="row">
          {item.needsReview && <Badge tone="warning" icon={<PenLine size={12} aria-hidden />}>Cần chấm tay</Badge>}
          <span className="mono small">Điểm hiện có {formatScore(item.awardedPoints)}/{formatScore(item.pointValue)}</span>
        </span>
      </div>
      <p className="prewrap small" style={{ color: 'var(--color-ink)' }}>{item.questionContent}</p>
      <div className="grade-answer small">
        <span className="xsmall muted">Bài làm của thí sinh</span>
        <p className="prewrap">{response?.trim() ? response : <em className="muted">Không trả lời</em>}</p>
      </div>
      <p className="xsmall muted">
        {item.questionType === 'ESSAY' ? `Gợi ý tự động (chỉ tham khảo): ${formatScore(item.autoScore)}` : `Điểm tự động: ${formatScore(item.autoScore)}`}
        {item.manualScore !== null && ` · Đã chấm tay: ${formatScore(item.manualScore)}`}
        {item.manualGradedAt && ` · ${formatDateTime(item.manualGradedAt, '')}`}
      </p>
      <div className="grade-score">
        <Field label={`Điểm (0–${formatScore(item.pointValue)})`} htmlFor={`${fid}-score`} error={problem?.score}>
          <input id={`${fid}-score`} type="number" min={0} max={item.pointValue ?? undefined} step={0.25} className="input" value={form.score} disabled={!editable}
            placeholder={item.needsReview ? 'Chưa chấm' : formatScore(item.awardedPoints)} aria-invalid={!!problem?.score} onChange={e => onChange({ score: e.target.value })} />
        </Field>
        <Field label="Nhận xét câu này" htmlFor={`${fid}-fb`}>
          <textarea id={`${fid}-fb`} className="input" rows={2} value={form.feedback} disabled={!editable} onChange={e => onChange({ feedback: e.target.value })} />
        </Field>
      </div>
      {showReason && (
        <Field label="Lý do sửa điểm tự động (nội bộ)" htmlFor={`${fid}-reason`} required error={problem?.reason} hint="Chỉ ghi vào nhật ký chấm bài, học sinh không thấy.">
          <input id={`${fid}-reason`} className="input" value={form.overrideReason} disabled={!editable} aria-invalid={!!problem?.reason} onChange={e => onChange({ overrideReason: e.target.value })} />
        </Field>
      )}
    </li>
  )
}

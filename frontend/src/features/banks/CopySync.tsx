import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient, type UseQueryResult } from '@tanstack/react-query'
import { AlertTriangle, RefreshCw } from 'lucide-react'
import { copySyncApi, type FieldChange, type QuestionSyncPreview, type SetItemChange, type SetSyncPreview, type SyncChoice, type SyncEvent, type SyncKind } from './copySyncApi'
import { ITEM_KIND_LABEL, SYNC_CHOICE_LABEL, SYNC_FIELD_LABEL, applyBody, canApply, decisionLabel, keepChoices, parseActions, syncRows, syncValue, tookSource, unresolvedByTab, type Decisions, type SyncRow, type SyncTab } from './copySyncModel'
import { bankApi, type PublicPostItem } from './bankApi'
import { excerpt, isStatus } from './bankModel'
import { Dialog } from '../../components/Dialog'
import { Badge, EmptyState, ErrorState, LoadingState, Notice } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { formatDateTime, shortId } from '../../lib/format'
import './banks.css'

type Preview = QuestionSyncPreview | SetSyncPreview
const previewKey = (kind: SyncKind, id: string) => ['copy-sync', kind, id]
const loadPreview = (kind: SyncKind, id: string): Promise<Preview> => kind === 'question' ? copySyncApi.question(id) : copySyncApi.set(id)

/** "Cập nhật từ nguồn" line for a copied Question or a forked Set. Silent when the resource is not a copy (404). */
export function CopySyncEntry({ kind, id }: { kind: SyncKind; id: string }) {
  const [open, setOpen] = useState(false)
  const preview = useQuery({
    queryKey: previewKey(kind, id), retry: false,
    queryFn: async () => { try { return await loadPreview(kind, id) } catch (e) { if (isStatus(e, 404)) return null; throw e } },
  })
  if (preview.isLoading || preview.data === null) return null
  if (preview.isError) return <p className="xsmall muted">Chưa kiểm tra được cập nhật từ nguồn. <button type="button" className="btn btn-ghost btn-sm" onClick={() => void preview.refetch()}>Thử lại</button></p>
  const p = preview.data!
  const rows = syncRows(p), conflicts = rows.filter(r => r.conflict).length
  const unavailable = p.availability === 'UNAVAILABLE'
  return (
    <section aria-label="Cập nhật từ nguồn" className="sync-entry" data-state={unavailable ? 'unavailable' : rows.length ? 'pending' : 'clean'}>
      <div style={{ minWidth: 0 }}>
        <span className="field-label">Cập nhật từ nguồn</span>
        <p className="small">{unavailable ? 'Nguồn không còn truy cập được · chỉ xem lịch sử'
          : rows.length ? <>Nguồn có <strong>{rows.length}</strong> thay đổi{conflicts ? <> · <strong>{conflicts}</strong> xung đột</> : null}</>
          : `Đã đồng bộ với nguồn phiên bản ${p.latestSourceVersion ?? p.referenceSourceVersion}`}</p>
      </div>
      <button type="button" className={`btn btn-sm ${rows.length ? 'btn-primary' : 'btn-secondary'}`} onClick={() => setOpen(true)}>
        <RefreshCw size={14} aria-hidden /> {rows.length ? 'Đồng bộ phiên bản' : 'Lịch sử đồng bộ'}
      </button>
      {open && <CopySyncDialog kind={kind} id={id} onClose={() => setOpen(false)} />}
    </section>
  )
}

type DialogTab = SyncTab | 'history'
const TAB_LABEL: Record<DialogTab, string> = { info: 'Thông tin', items: 'Câu hỏi', order: 'Thứ tự', history: 'Lịch sử' }

function CopySyncDialog({ kind, id, onClose }: { kind: SyncKind; id: string; onClose: () => void }) {
  const qc = useQueryClient()
  const preview = useQuery({ queryKey: previewKey(kind, id), queryFn: () => loadPreview(kind, id), retry: false })
  const setQuery = useQuery({ queryKey: ['copy-sync', 'set-items', id], queryFn: () => bankApi.questionSet(id), enabled: kind === 'set' })
  const [decisions, setDecisions] = useState<Decisions>({})
  const [picked, setTab] = useState<DialogTab | null>(null)
  const [notice, setNotice] = useState<{ tone: 'success' | 'warning' | 'danger'; text: string } | null>(null)
  const [draftCreated, setDraftCreated] = useState(false)
  const p = preview.data
  const rows = p ? syncRows(p) : []
  const available = p?.availability === 'AVAILABLE'
  const tabs: DialogTab[] = !available ? ['history'] : kind === 'question' ? ['info', 'history'] : ['info', 'items', 'order', 'history']
  const tab: DialogTab = picked && tabs.includes(picked) ? picked : tabs.find(t => rows.some(r => r.tab === t)) ?? tabs[0]
  const unresolved = unresolvedByTab(rows, decisions)
  const history = useQuery({ queryKey: ['copy-sync', kind, id, 'history'], queryFn: () => copySyncApi.history(kind, id), enabled: tab === 'history' })

  const apply = useMutation({
    mutationFn: () => copySyncApi.apply<Preview>(kind, id, applyBody(p!, rows, decisions)),
    onSuccess: async next => {
      const created = tookSource(keepChoices(rows, decisions))
      qc.setQueryData(previewKey(kind, id), next)
      setDecisions({}); setDraftCreated(created)
      setNotice({ tone: 'success', text: created
        ? 'Đã áp dụng. Phần lấy từ nguồn được lưu thành bản nháp (DRAFT) mới; cần kích hoạt trước khi dùng trong đề thi hoặc xuất bản.'
        : 'Đã ghi nhận lựa chọn giữ bản của bạn; nội dung không đổi.' })
      if (kind === 'set' && created) setNotice(n => n && { ...n, text: `${n.text} Bộ đã có phiên bản mới: tải lại trang trước khi sửa bộ.` })
      await Promise.all([
        qc.invalidateQueries({ queryKey: ['copy-sync', kind, id, 'history'] }), qc.invalidateQueries({ queryKey: ['copy-sync', 'set-items', id] }),
        qc.invalidateQueries({ queryKey: ['question', id] }), qc.invalidateQueries({ queryKey: ['versions', id] }), qc.invalidateQueries({ queryKey: ['questions'] }),
        qc.invalidateQueries({ queryKey: ['banks'] }),
      ])
    },
    onError: async e => {
      if (!isStatus(e, 409)) { setNotice({ tone: 'danger', text: errorText(e) }); return }
      const fresh = await preview.refetch()
      if (fresh.data) setDecisions(d => keepChoices(syncRows(fresh.data!), d))
      setNotice({ tone: 'warning', text: 'Dữ liệu đã thay đổi, đã tải lại so sánh. Lựa chọn còn hợp lệ được giữ; kiểm tra lại trước khi áp dụng.' })
    },
  })

  const choose = (key: string, choice: SyncChoice | null) => setDecisions(d => {
    const next = { ...d }
    if (choice) next[key] = choice; else delete next[key]
    return next
  })
  const drafts = (setQuery.data?.items ?? []).filter(i => i.question.status === 'DRAFT')

  return (
    <Dialog size="lg" title={kind === 'question' ? 'Đồng bộ phiên bản câu hỏi' : 'Đồng bộ phiên bản bộ câu hỏi'} onClose={onClose} busy={apply.isPending}
      description={p && (available
        ? `Bản của bạn v${p.localVersion} · gốc lúc sao chép v${p.referenceSourceVersion} · nguồn mới nhất v${p.latestSourceVersion}. Chọn cho từng thay đổi; không có gộp tự động.`
        : 'Nguồn không còn truy cập được (đã gỡ, lưu trữ hoặc thu hồi quyền). Chỉ xem lịch sử.')}
      footer={<>
        <button type="button" className="btn btn-secondary" onClick={onClose} disabled={apply.isPending}>Đóng</button>
        {available && rows.length > 0 && <button type="button" className="btn btn-primary" disabled={!canApply(rows, decisions) || apply.isPending} aria-busy={apply.isPending} onClick={() => apply.mutate()}>
          {apply.isPending ? 'Đang áp dụng…' : `Áp dụng ${Object.keys(keepChoices(rows, decisions)).length} lựa chọn`}
        </button>}
      </>}>
      {preview.isLoading ? <LoadingState rows={4} /> : preview.isError || !p ? <ErrorState error={preview.error} onRetry={() => void preview.refetch()} /> : <div className="stack">
        {!available && <Notice tone="warning" title="Chỉ xem lịch sử">Nguồn không còn truy cập được (đã gỡ, lưu trữ hoặc thu hồi). Không thể áp dụng thay đổi mới.</Notice>}
        {notice && <Notice tone={notice.tone} live>{notice.text}</Notice>}
        {draftCreated && (kind === 'question'
          ? <div className="row"><button type="button" className="btn btn-secondary btn-sm" onClick={onClose}>Mở câu hỏi (bản nháp mới)</button></div>
          : drafts.length > 0 && <section aria-label="Câu bản nháp cần kích hoạt" className="stack-sm"><span className="field-label">Câu bản nháp cần kích hoạt ({drafts.length})</span>
            <ul className="sync-drafts">{drafts.map(d => <li key={d.id}><Link to={`/questions?q=${d.sourceQuestionId}`}>{excerpt(d.question.content, 100)}</Link><span className="xsmall muted"> · v{d.sourceQuestionVersion}</span></li>)}</ul>
            <p className="xsmall muted">Kích hoạt từng câu rồi cập nhật bộ qua Sửa bộ trước khi đăng công khai.</p></section>)}
        <div className="tabs" role="tablist" aria-label="Phần đồng bộ">
          {tabs.map(t => <button key={t} type="button" role="tab" className="tab" aria-selected={tab === t} onClick={() => setTab(t)}>
            {t === 'info' && kind === 'question' ? 'So sánh' : TAB_LABEL[t]}{t !== 'history' && unresolved[t] > 0 ? <span className="count" aria-label={`${unresolved[t]} xung đột chưa chọn`}>⚠ {unresolved[t]}</span> : t !== 'history' && rows.some(r => r.tab === t) ? <span className="count">{rows.filter(r => r.tab === t).length}</span> : null}
          </button>)}
        </div>
        <div role="tabpanel">
          {tab === 'history' ? <History query={history} />
            : tab === 'info' ? <FieldTable changes={'changes' in p ? p.changes : p.metadataChanges} decisions={decisions} onChoose={choose} />
            : tab === 'items' && 'itemChanges' in p ? <ItemList preview={p} rows={rows} local={setQuery.data?.items ?? []} decisions={decisions} onChoose={choose} />
            : 'itemChanges' in p ? <OrderPanel preview={p} local={setQuery.data?.items ?? []} decisions={decisions} onChoose={choose} /> : null}
        </div>
      </div>}
    </Dialog>
  )
}

function Choice({ name, value, decisions, conflict, labels, onChoose }: {
  name: string; value?: string; decisions: Decisions; conflict: boolean; labels?: Partial<Record<SyncChoice, string>>; onChoose: (key: string, c: SyncChoice | null) => void
}) {
  const current = decisions[name]
  return (
    <fieldset className="sync-choice">
      <legend className="sr-only">Lựa chọn cho {value ?? decisionLabel(name)}</legend>
      {(['KEEP_LOCAL', 'TAKE_SOURCE'] as SyncChoice[]).map(c => <label key={c} className="check small">
        <input type="radio" name={`sync-${name}`} checked={current === c} onChange={() => onChoose(name, c)} /> {labels?.[c] ?? SYNC_CHOICE_LABEL[c]}
      </label>)}
      {current && !conflict && <button type="button" className="btn btn-ghost btn-sm" onClick={() => onChoose(name, null)}>Bỏ qua</button>}
    </fieldset>
  )
}

function ConflictMark({ conflict, decided }: { conflict: boolean; decided: boolean }) {
  if (!conflict) return null
  return <Badge tone={decided ? 'neutral' : 'danger'} icon={<AlertTriangle size={12} aria-hidden />}>{decided ? 'Xung đột · đã chọn' : 'Xung đột – bắt buộc chọn'}</Badge>
}

function FieldTable({ changes, decisions, onChoose }: { changes: FieldChange[]; decisions: Decisions; onChoose: (key: string, c: SyncChoice | null) => void }) {
  const shown = changes.filter(c => c.sourceChanged).sort((a, b) => Number(b.conflict) - Number(a.conflict))
  if (!shown.length) return <EmptyState title="Không có thay đổi ở phần này" description="Nguồn không đổi các trường thông tin kể từ lần đồng bộ trước." />
  return (
    <div className="sync-list">
      {shown.map(c => <article key={c.field} className="sync-row" data-conflict={c.conflict && !decisions[c.field] ? 'open' : c.conflict ? 'done' : undefined}>
        <header className="row" style={{ justifyContent: 'space-between' }}>
          <strong>{SYNC_FIELD_LABEL[c.field] ?? c.field}</strong>
          <ConflictMark conflict={c.conflict} decided={!!decisions[c.field]} />
        </header>
        {c.redacted ? <div className="stack-sm">
          <div className="row">{c.sourceChanged && <Badge tone="info">Nguồn đã thay đổi</Badge>}{c.localChanged && <Badge>Bạn đã sửa</Badge>}</div>
          <p className="xsmall muted">Nội dung đáp án không hiển thị khi so sánh.</p>
        </div> : <div className="sync-values">
          <Value label="Gốc lúc sao chép" text={syncValue(c.field, c.reference)} />
          <Value label="Bản của bạn" text={syncValue(c.field, c.local)} flag={c.localChanged ? 'Bạn đã sửa' : undefined} />
          <Value label="Nguồn mới nhất" text={syncValue(c.field, c.latest)} flag="Nguồn đã thay đổi" />
        </div>}
        <Choice name={c.field} value={SYNC_FIELD_LABEL[c.field]} decisions={decisions} conflict={c.conflict} onChoose={onChoose} />
      </article>)}
    </div>
  )
}

function Value({ label, text, flag }: { label: string; text: string | null; flag?: string }) {
  const long = (text?.length ?? 0) > 160
  return (
    <div className="sync-value">
      <span className="xsmall muted">{label}{flag && <> · {flag}</>}</span>
      {text === null ? <span className="small muted">(trống)</span> : long
        ? <details><summary className="small prewrap">{text.slice(0, 160)}…</summary><p className="small prewrap">{text}</p></details>
        : <p className="small prewrap">{text}</p>}
    </div>
  )
}

const ITEM_LABELS: Record<SetItemChange['kind'], Record<SyncChoice, string>> = {
  ADDED: { KEEP_LOCAL: 'Không thêm', TAKE_SOURCE: 'Thêm vào bộ (bản nháp mới)' },
  UPDATED: { KEEP_LOCAL: 'Giữ câu của tôi', TAKE_SOURCE: 'Lấy bản nguồn (bản nháp mới)' },
  REMOVED: { KEEP_LOCAL: 'Giữ trong bộ', TAKE_SOURCE: 'Bỏ khỏi bộ' },
}

function ItemList({ preview, rows, local, decisions, onChoose }: { preview: SetSyncPreview; rows: SyncRow[]; local: PublicPostItem[]; decisions: Decisions; onChoose: (key: string, c: SyncChoice | null) => void }) {
  const byKey = new Map(preview.itemChanges.map(i => [`ITEM:${i.sourceQuestionId}`, i]))
  const items = rows.filter(r => r.tab === 'items').map(r => byKey.get(r.key)!).filter(Boolean)
  if (!items.length) return <EmptyState title="Không có thay đổi câu hỏi" description="Nguồn không thêm, sửa hay bỏ câu nào kể từ lần đồng bộ trước." />
  const content = new Map(local.map(i => [i.sourceQuestionId, i.question.content]))
  return (
    <div className="sync-list">
      {items.map(i => { const key = `ITEM:${i.sourceQuestionId}`, text = i.localQuestionId ? content.get(i.localQuestionId) : undefined
        return <article key={key} className="sync-row" data-conflict={i.conflict && !decisions[key] ? 'open' : i.conflict ? 'done' : undefined}>
          <header className="row" style={{ justifyContent: 'space-between' }}>
            <span className="row" style={{ gap: 8 }}><Badge tone={i.kind === 'REMOVED' ? 'danger' : i.kind === 'ADDED' ? 'info' : 'neutral'}>{ITEM_KIND_LABEL[i.kind]}</Badge>
              <strong className="small">{text ? excerpt(text, 110) : `Câu nguồn ${shortId(i.sourceQuestionId)}`}</strong></span>
            <ConflictMark conflict={i.conflict} decided={!!decisions[key]} />
          </header>
          <p className="xsmall muted">{[
            i.kind === 'ADDED' ? `Nguồn thêm câu mới (v${i.latestVersion}) ở vị trí ${(i.latestPosition ?? 0) + 1}` : i.kind === 'REMOVED' ? 'Nguồn đã bỏ câu này' : `Nguồn cập nhật v${i.referenceVersion} → v${i.latestVersion}`,
            i.localChanged ? (i.localQuestionId ? 'Bạn đã sửa câu này' : 'Bạn đã bỏ câu này khỏi bộ') : '',
          ].filter(Boolean).join(' · ')}</p>
          <Choice name={key} value={text ? excerpt(text, 40) : shortId(i.sourceQuestionId)} decisions={decisions} conflict={i.conflict} labels={ITEM_LABELS[i.kind]} onChoose={onChoose} />
        </article> })}
    </div>
  )
}

function OrderPanel({ preview, local, decisions, onChoose }: { preview: SetSyncPreview; local: PublicPostItem[]; decisions: Decisions; onChoose: (key: string, c: SyncChoice | null) => void }) {
  if (!preview.sourceReordered) return <EmptyState title="Nguồn không đổi thứ tự" description={preview.localReordered ? 'Bạn đã sắp xếp lại; thứ tự của bạn được giữ.' : 'Thứ tự các câu chung giống nhau.'} />
  const latest = new Map(preview.itemChanges.filter(i => i.localQuestionId && i.latestPosition !== null).map(i => [i.localQuestionId!, i.latestPosition!]))
  const ordered = [...local].sort((a, b) => a.position - b.position)
  return (
    <div className="stack">
      <article className="sync-row" data-conflict={preview.reorderConflict && !decisions.ORDER ? 'open' : preview.reorderConflict ? 'done' : undefined}>
        <header className="row" style={{ justifyContent: 'space-between' }}><strong>Thứ tự câu</strong><ConflictMark conflict={preview.reorderConflict} decided={!!decisions.ORDER} /></header>
        <p className="xsmall muted">Nguồn đã sắp xếp lại các câu chung{preview.localReordered ? '; bạn cũng đã sắp xếp lại' : ''}.</p>
        <Choice name="ORDER" value="thứ tự câu" decisions={decisions} conflict={preview.reorderConflict} labels={{ KEEP_LOCAL: 'Giữ thứ tự của tôi', TAKE_SOURCE: 'Theo thứ tự nguồn' }} onChoose={onChoose} />
      </article>
      <div className="table-wrap"><table className="bk-table">
        <thead><tr><th scope="col">Thứ tự hiện tại</th><th scope="col">Câu</th><th scope="col">Vị trí trong nguồn</th></tr></thead>
        <tbody>{ordered.map((i, n) => <tr key={i.id}><td>{n + 1}</td><td className="small">{excerpt(i.question.content, 90)}</td><td>{latest.has(i.sourceQuestionId) ? latest.get(i.sourceQuestionId)! + 1 : '—'}</td></tr>)}</tbody>
      </table></div>
      <p className="xsmall muted">Vị trí trong nguồn chỉ có cho câu nguồn đã thay đổi; câu khác hiện “—”.</p>
    </div>
  )
}

function History({ query }: { query: UseQueryResult<SyncEvent[]> }) {
  if (query.isLoading) return <LoadingState rows={3} />
  if (query.isError) return <ErrorState error={query.error} onRetry={() => void query.refetch()} />
  if (!query.data?.length) return <EmptyState title="Chưa có lần đồng bộ nào" description="Mỗi lần áp dụng lựa chọn sẽ được ghi lại ở đây." />
  return (
    <div className="table-wrap"><table className="bk-table">
      <thead><tr><th scope="col">Thời gian</th><th scope="col">Người thực hiện</th><th scope="col">Phiên bản</th><th scope="col">Lựa chọn</th></tr></thead>
      <tbody>{query.data.map(e => <tr key={e.id}>
        <td className="small">{formatDateTime(e.createdAt)}</td><td className="small">{shortId(e.actorUserId)}</td>
        <td className="small">Bản của bạn v{e.localVersion} · nguồn v{e.sourceVersion}</td>
        <td className="small">{parseActions(e.actions).map(a => <div key={a.key}>{decisionLabel(a.key)}: {SYNC_CHOICE_LABEL[a.choice as SyncChoice] ?? a.choice}</div>)}</td>
      </tr>)}</tbody>
    </table></div>
  )
}

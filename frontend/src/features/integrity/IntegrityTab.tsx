import { useRef, useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { RefreshCw, X } from 'lucide-react'
import { integrityApi, type IntegrityPolicy, type SignalEvent, type SignalType } from './integrityApi'
import { AUDIT_LABEL, BUDGET_SIGNALS, SIGNAL_LABEL, integrityEnded, metadataText, policySummary } from './integrityModel'
import { GrantDialog } from '../participants/ParticipantDialogs'
import { useModalFocus } from '../../components/useModalFocus'
import { Badge, EmptyState, ErrorState, LoadingState, Notice, Pagination, Section } from '../../components/ui'
import { useIsNarrow } from '../../lib/hooks'
import { formatDateTime, shortId } from '../../lib/format'
import '../participants/participants.css'
import { t as tt, useT } from '../../lib/i18n'

const TYPES = Object.keys(SIGNAL_LABEL) as SignalType[]

/** Staff review of browser signals for one Exam (OWNER/MANAGER/PROCTOR). Evidence only; wording stays neutral. */
export default function IntegrityTab({ examId, policy, manage, live }: { examId: string; policy?: IntegrityPolicy | null; manage: boolean; live: boolean }) {
  useT()
  const [type, setType] = useState<SignalType | ''>('')
  const [student, setStudent] = useState('')
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<SignalEvent | null>(null)
  const narrow = useIsNarrow(1100)
  const events = useQuery({
    queryKey: ['integrity', 'exam-events', examId, type, page], queryFn: () => integrityApi.examEvents(examId, { type: type || undefined, page }),
    refetchInterval: live ? 15_000 : false, retry: false,
  })
  const needle = student.trim().toLowerCase()
  const rows = (events.data?.content ?? []).filter(e => !needle || e.userId.toLowerCase().startsWith(needle))

  return (
    <div className="stack-lg">
      <Section title={tt('Giám sát trình duyệt')} description={tt('Cấu hình đã cố định: {0}.', { 0: policySummary(policy) })}>
        <p className="small muted">{tt('Tín hiệu do trình duyệt của thí sinh báo về, là dữ liệu ghi nhận và không phải kết luận gian lận. Chỉ giáo viên có vai trò trong kỳ thi xem được.')}</p>
      </Section>
      <Section title={tt('Tín hiệu đã ghi nhận')} bodyClassName=""
        description={events.data ? tt('{0} tín hiệu · theo thời điểm máy chủ nhận', { 0: events.data.totalElements }) : undefined}
        actions={<button type="button" className="btn btn-ghost btn-sm" onClick={() => void events.refetch()} disabled={events.isFetching}><RefreshCw size={14} aria-hidden /> {tt('Làm mới')}</button>}>
        <div className="card-body filter-row">
          <label className="field"><span className="field-label">{tt('Loại tín hiệu')}</span>
            <select className="input" value={type} onChange={e => { setType(e.target.value as SignalType | ''); setPage(0) }}>
              <option value="">{tt('Tất cả')}</option>{TYPES.map(t => <option key={t} value={t}>{SIGNAL_LABEL[t]}</option>)}
            </select></label>
          <label className="field filter-grow"><span className="field-label">{tt('Thí sinh (mã)')}</span>
            <input className="input" type="search" value={student} placeholder={tt('Nhập đầu mã người dùng')} onChange={e => setStudent(e.target.value)} /></label>
        </div>
        {needle && <p className="card-body xsmall muted" style={{ paddingTop: 0 }}>{tt('Lọc theo thí sinh áp dụng cho trang đang xem.')}</p>}
        <div className="pt-layout" data-panel={selected ? 'open' : 'closed'}>
          <div>
            {events.isLoading ? <LoadingState rows={3} /> : events.isError ? <ErrorState error={events.error} onRetry={() => void events.refetch()} />
              : rows.length === 0 ? <EmptyState title={tt('Chưa có tín hiệu')} description={tt('Tín hiệu xuất hiện khi thí sinh làm bài với giám sát được bật.')} />
              : <div className="table-wrap"><table className="pt-table">
                <thead><tr><th scope="col">{tt('Thời gian nhận')}</th><th scope="col">{tt('Thí sinh')}</th><th scope="col">{tt('Tín hiệu')}</th><th scope="col">{tt('Chi tiết')}</th></tr></thead>
                <tbody>{rows.map(e => <tr key={e.id} aria-selected={selected?.sessionId === e.sessionId}>
                  <td className="small">{formatDateTime(e.receivedAt)}</td>
                  <td><button type="button" className="pt-row-btn mono small" onClick={() => setSelected(e)}>{shortId(e.userId)}</button></td>
                  <td className="small">{SIGNAL_LABEL[e.type] ?? e.type}{BUDGET_SIGNALS.includes(e.type) && <span className="xsmall muted"> {tt('· tính ngưỡng')}</span>}</td>
                  <td className="xsmall muted">{metadataText(e.metadata)}</td>
                </tr>)}</tbody>
              </table></div>}
            {events.data && events.data.totalPages > 1 && <Pagination label={tt('Phân trang tín hiệu')} page={page} totalPages={events.data.totalPages} onChange={setPage} />}
          </div>
          {selected && <SessionPanel key={selected.sessionId} examId={examId} event={selected} manage={manage} narrow={narrow} onClose={() => setSelected(null)} />}
        </div>
      </Section>
    </div>
  )
}

function SessionPanel({ examId, event, manage, narrow, onClose }: { examId: string; event: SignalEvent; manage: boolean; narrow: boolean; onClose: () => void }) {
  useT()
  const panelRef = useRef<HTMLElement>(null), headingRef = useRef<HTMLHeadingElement>(null)
  const [granting, setGranting] = useState(false)
  const [flash, setFlash] = useState('')
  useModalFocus(panelRef, { trap: narrow, escape: true, onEscape: onClose, initialFocus: () => headingRef.current })
  const summary = useQuery({ queryKey: ['integrity', 'summary', event.sessionId], queryFn: () => integrityApi.summary(event.sessionId), retry: false })
  const timeline = useQuery({ queryKey: ['integrity', 'session-events', event.sessionId], queryFn: () => integrityApi.sessionEvents(event.sessionId, 0), retry: false })
  const audit = useQuery({ queryKey: ['integrity', 'audit', event.sessionId], queryFn: () => integrityApi.audit(event.sessionId), retry: false })
  const counts = summary.data ? TYPES.filter(t => (summary.data!.counts[t] ?? 0) > 0) : []
  const budget = summary.data ? BUDGET_SIGNALS.reduce((n, t) => n + (summary.data!.counts[t] ?? 0), 0) : null
  const ended = integrityEnded(audit.data?.content ?? [])
  const name = tt('Thí sinh {0}', { 0: shortId(event.userId) })

  return (
    <>
      {narrow && <button type="button" className="pt-sheet-backdrop" aria-label={tt('Đóng chi tiết lượt làm bài')} onClick={onClose} tabIndex={-1} />}
      <aside ref={panelRef} className="pt-panel" role={narrow ? 'dialog' : 'complementary'} aria-modal={narrow || undefined} aria-label={tt('Tín hiệu của lượt làm bài')}>
        <div className="pt-panel-head">
          <div style={{ minWidth: 0 }}>
            <h2 ref={headingRef} tabIndex={-1} style={{ fontSize: '1.2rem' }}>{name}</h2>
            <p className="xsmall muted">{tt('Lượt')} <span className="mono">{shortId(event.sessionId)}</span> {tt('· mã người dùng')} <span className="mono">{event.userId}</span></p>
          </div>
          <button type="button" className="btn btn-ghost btn-icon" aria-label={tt('Đóng')} onClick={onClose}><X size={18} /></button>
        </div>
        <div className="pt-panel-body">
          {flash && <Notice tone="success" live>{flash}</Notice>}
          <section className="stack-sm" aria-label={tt('Tổng hợp')}>
            <span className="field-label">{tt('Tổng hợp')}</span>
            {summary.isLoading ? <LoadingState rows={1} /> : summary.isError ? <ErrorState error={summary.error} onRetry={() => void summary.refetch()} /> : <>
              <p className="small">{summary.data!.totalEvents} {tt('tín hiệu ·')} <strong>{budget}</strong> {tt('tính vào ngưỡng')}</p>
              <div className="row" style={{ gap: 6 }}>{counts.map(t => <Badge key={t} tone={BUDGET_SIGNALS.includes(t) ? 'neutral' : 'muted'}>{SIGNAL_LABEL[t]} · {summary.data!.counts[t]}</Badge>)}</div>
            </>}
          </section>
          <section className="stack-sm" aria-label={tt('Nhật ký hệ thống')}>
            <span className="field-label">{tt('Nhật ký hệ thống')}</span>
            {audit.isLoading ? <LoadingState rows={1} /> : audit.isError ? <ErrorState error={audit.error} onRetry={() => void audit.refetch()} />
              : !audit.data!.content.length ? <p className="small muted">{tt('Không có thao tác hệ thống trên lượt này.')}</p>
              : <ul className="pt-list">{audit.data!.content.map(a => <li key={a.id} className="pt-attempt">
                <strong className="small">{AUDIT_LABEL[a.action] ?? a.action}</strong>
                <span className="xsmall muted">{formatDateTime(a.occurredAt)} · {a.actorType === 'SYSTEM' ? tt('Hệ thống') : tt('Người thực hiện {0}', { 0: shortId(a.actorUserId) })}{a.eventCount != null ? tt(' · {0} tín hiệu', { 0: a.eventCount }) : ''}</span>
                {a.actorType !== 'SYSTEM' && a.metadata && <span className="xsmall">{tt('Lý do:')} {a.metadata}</span>}
              </li>)}</ul>}
            {ended && manage && <button type="button" className="btn btn-primary btn-sm" style={{ alignSelf: 'flex-start', justifySelf: 'start' }} onClick={() => setGranting(true)}>{tt('Cấp lượt mới')}</button>}
            {ended && !manage && <p className="xsmall muted">{tt('Chủ sở hữu hoặc Quản lý có thể cấp lượt mới cho thí sinh này.')}</p>}
          </section>
          <section className="stack-sm" aria-label={tt('Dòng thời gian')}>
            <span className="field-label">{tt('Dòng thời gian')}</span>
            {timeline.isLoading ? <LoadingState rows={2} /> : timeline.isError ? <ErrorState error={timeline.error} onRetry={() => void timeline.refetch()} />
              : <ol className="pt-list">{timeline.data!.content.map(e => <li key={e.id} className="small">
                <span className="mono xsmall muted">{formatDateTime(e.receivedAt)}</span> · {SIGNAL_LABEL[e.type] ?? e.type}{metadataText(e.metadata) && <span className="xsmall muted"> · {metadataText(e.metadata)}</span>}
              </li>)}</ol>}
            {timeline.data && timeline.data.totalElements > timeline.data.content.length && <p className="xsmall muted">{tt('Hiển thị')} {timeline.data.content.length}/{timeline.data.totalElements} {tt('tín hiệu đầu tiên.')}</p>}
          </section>
        </div>
      </aside>
      {granting && <GrantDialog examId={examId} userId={event.userId} name={name} onClose={() => setGranting(false)}
        onDone={() => { setGranting(false); setFlash(tt('Đã cấp lượt mới. Lượt đã tự nộp vẫn giữ nguyên.')) }} />}
    </>
  )
}

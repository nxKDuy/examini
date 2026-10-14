import { useState, type FormEvent } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Search, UserPlus, Users } from 'lucide-react'
import './participants.css'
import { participantApi, type ParticipantRow } from './participantApi'
import { SOURCE_LABEL, STATUS_LABEL, attemptSummary, canAddParticipants, participantName } from './participantModel'
import { AddParticipantsDialog } from './ParticipantDialogs'
import ParticipantPanel from './ParticipantPanel'
import { Badge, EmptyState, ErrorState, Field, LoadingState, Notice, Pagination } from '../../components/ui'
import { formatDateTime } from '../../lib/format'
import { useIsNarrow } from '../../lib/hooks'
import { t, useT, i18nMap } from '../../lib/i18n'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
const STATUS = i18nMap([{ value: 'ACTIVE', label: 'Đủ điều kiện' }, { value: 'REVOKED', label: 'Lịch sử: đã gỡ' }, { value: '', label: 'Tất cả' }])

/** Roster of one Exam: server counts, status/source filters, search by user id (the only search the API has), pages of 50. */
export default function ParticipantsSection({ examId, examStatus, maxParticipants, manage }: { examId: string; examStatus: string; maxParticipants: number; manage: boolean }) {
  useT()
  const [status, setStatus] = useState('ACTIVE')
  const [source, setSource] = useState('')
  const [search, setSearch] = useState('')
  const [userId, setUserId] = useState('')
  const [page, setPage] = useState(0)
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [adding, setAdding] = useState(false)
  const narrowList = useIsNarrow(899), narrowPanel = useIsNarrow(1100)

  const list = useQuery({
    queryKey: ['participants', 'roster', examId, status, source, userId, page],
    queryFn: () => participantApi.roster(examId, { status: status || undefined, source: source || undefined, userId: userId || undefined, page }),
    placeholderData: prev => prev,
  })
  const rows = list.data?.content ?? []
  const counts = list.data?.counts
  const selected = rows.find(r => r.userId === selectedId) ?? null
  const reset = (fn: () => void) => { fn(); setPage(0); setSelectedId(null) }
  const searchInvalid = search.trim() !== '' && !UUID.test(search.trim())
  function submitSearch(e: FormEvent) { e.preventDefault(); if (!searchInvalid) reset(() => setUserId(search.trim())) }

  return (
    <div className="stack">
      <div className="pt-counts" role="group" aria-label={t('Số liệu thí sinh')}>
        <div><div className="n">{counts?.registered ?? '-'}</div><div className="l">{t('Đã đăng ký')}</div></div>
        <div><div className="n">{counts ? `${counts.eligible}/${maxParticipants}` : '-'}</div><div className="l">{t('Đủ điều kiện (chiếm chỗ)')}</div></div>
        <div><div className="n">{counts?.revoked ?? '-'}</div><div className="l">{t('Đã gỡ')}</div></div>
      </div>
      <p className="xsmall muted">{t('Số liệu của toàn kỳ thi, không chỉ trang đang xem. Người đã gỡ không chiếm chỗ nhưng vẫn nằm trong lịch sử.')}</p>

      <div className="row" style={{ justifyContent: 'space-between' }}>
        <div className="row">
          <label className="row small" style={{ gap: 6 }}>{t('Trạng thái')}
            <select className="input" value={status} onChange={e => reset(() => setStatus(e.target.value))} style={{ width: 'auto' }}>{STATUS.map(s => <option key={s.value} value={s.value}>{s.label}</option>)}</select></label>
          <label className="row small" style={{ gap: 6 }}>{t('Nguồn')}
            <select className="input" value={source} onChange={e => reset(() => setSource(e.target.value))} style={{ width: 'auto' }}>
              <option value="">{t('Tất cả')}</option>{Object.entries(SOURCE_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></label>
        </div>
        {canAddParticipants(manage, examStatus) && <button type="button" className="btn btn-primary" onClick={() => setAdding(true)}><UserPlus size={16} aria-hidden /> {t('Thêm thí sinh')}</button>}
      </div>
      {manage && examStatus === 'CLOSED' && <Notice tone="info">{t('Kỳ thi đã đóng nên không thêm thí sinh mới.')}</Notice>}
      <form onSubmit={submitSearch} className="row" style={{ alignItems: 'flex-end', flexWrap: 'nowrap' }}>
        <div style={{ flex: 1 }}><Field label={t('Tìm theo mã người dùng')} htmlFor="pt-search" error={searchInvalid ? t('Nhập đủ mã người dùng (UUID). API chưa tìm theo tên hoặc email.') : undefined}>
          <input id="pt-search" className="input mono" value={search} placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx" onChange={e => setSearch(e.target.value)} aria-invalid={searchInvalid} /></Field></div>
        <button type="submit" className="btn btn-secondary" disabled={searchInvalid || search.trim() === ''}><Search size={16} aria-hidden /> {t('Tìm')}</button>
        {userId && <button type="button" className="btn btn-ghost" onClick={() => reset(() => { setUserId(''); setSearch('') })}>{t('Bỏ tìm')}</button>}
      </form>

      <div className="pt-layout" data-panel={selected ? 'open' : 'closed'}>
        <section className="card" aria-label={t('Danh sách thí sinh')} style={{ overflow: 'hidden', minWidth: 0 }}>
          {list.isLoading ? <LoadingState rows={3} />
            : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} />
            : rows.length === 0 ? <EmptyState icon={<Users size={28} />} title={t('Không có thí sinh')} description={userId || source || status !== 'ACTIVE' ? t('Không có ai khớp bộ lọc này.') : t('Thêm thí sinh hoặc chờ họ tham gia.')} />
            : narrowList ? rows.map(r => <RosterCard key={r.userId} r={r} active={selectedId === r.userId} onOpen={() => setSelectedId(r.userId)} />)
            : <table className="pt-table">
                <thead><tr><th scope="col">{t('Thí sinh')}</th><th scope="col">{t('Nguồn')}</th><th scope="col">{t('Trạng thái')}</th><th scope="col">{t('Lượt đã dùng')}</th><th scope="col">Tham gia</th></tr></thead>
                <tbody>{rows.map(r => {
                  const n = participantName(r)
                  return (
                    <tr key={r.userId} aria-selected={selectedId === r.userId}>
                      <td><button type="button" className="pt-row-btn" onClick={() => setSelectedId(r.userId)} aria-label={t('Xem chi tiết {0}', { 0: n.text })}>{n.text}</button>{n.fallback && <span className="xsmall muted"> {t('(chưa có tên)')}</span>}</td>
                      <td>{SOURCE_LABEL[r.source] ?? r.source}</td>
                      <td><Badge tone={r.status === 'ACTIVE' ? 'success' : 'muted'}>{STATUS_LABEL[r.status] ?? r.status}</Badge></td>
                      <td className="mono">{attemptSummary(r)}</td>
                      <td>{formatDateTime(r.joinedAt, '-')}</td>
                    </tr>
                  )
                })}</tbody>
              </table>}
          {list.data && <Pagination label={t('Phân trang thí sinh')} page={page} totalPages={list.data.totalPages} onChange={p => { setPage(p); setSelectedId(null) }} />}
        </section>
        {selected && <ParticipantPanel key={selected.userId} examId={examId} row={selected} manage={manage} narrow={narrowPanel} onClose={() => setSelectedId(null)} onChanged={() => void list.refetch()} />}
      </div>
      {adding && <AddParticipantsDialog examId={examId} onClose={() => setAdding(false)} onChanged={() => void list.refetch()} />}
    </div>
  )
}

function RosterCard({ r, active, onOpen }: { r: ParticipantRow; active: boolean; onOpen: () => void }) {
  useT()
  const n = participantName(r)
  return (
    <button type="button" className="pt-card" aria-current={active} onClick={onOpen}>
      <span className="spread"><strong>{n.text}</strong><Badge tone={r.status === 'ACTIVE' ? 'success' : 'muted'}>{STATUS_LABEL[r.status] ?? r.status}</Badge></span>
      <span className="row xsmall muted" style={{ gap: 8 }}><span>{SOURCE_LABEL[r.source] ?? r.source}</span><span>{t('Lượt')} {attemptSummary(r)}</span><span>Tham gia {formatDateTime(r.joinedAt, '-')}</span></span>
    </button>
  )
}

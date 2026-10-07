import { AlertTriangle, Ban, CheckCircle2, Clock, FileEdit, KeyRound, Lock, PlayCircle, Send } from 'lucide-react'
import './calendar.css'
import { Badge } from '../../components/ui'
import type { CalendarItem } from './calendarApi'
import { cardMetrics, itemSpan, stateInfo, windowText, type StateInfo } from './calendarModel'
import { formatTimeIn, offsetMinutes, zoneLabel } from '../../lib/zone'

const ICONS = { play: PlayCircle, clock: Clock, lock: Lock, edit: FileEdit, check: CheckCircle2, alert: AlertTriangle, send: Send, key: KeyRound, ban: Ban }

export function StateBadge({ state }: { state: string }) {
  const info: StateInfo = stateInfo(state)
  const Icon = ICONS[info.icon]
  return <Badge tone={info.tone} icon={<Icon size={12} aria-hidden />}>{info.label}</Badge>
}

const summary = (item: CalendarItem, viewerTz: string) => `${item.examTitle}, ${stateInfo(item.calendarState).label}, ${windowText(item, viewerTz).primary}`

export function AgendaRow({ item, nowMs, viewerTz, selected, onSelect }: {
  item: CalendarItem; nowMs: number; viewerTz: string; selected: boolean; onSelect: (item: CalendarItem) => void
}) {
  const span = itemSpan(item)
  const w = windowText(item, viewerTz)
  const tz = item.timezone
  // When a DST change falls inside the window the two ends have different offsets: say which one each time is.
  const split = !!span && span.kind === 'window' && offsetMinutes(span.start, tz) !== offsetMinutes(span.end, tz)
  const tag = (ms: number) => (split ? ` ${zoneLabel(ms, tz)}` : '')
  const clock = !span ? '–' : span.kind === 'window' ? `${formatTimeIn(span.start, tz)}${tag(span.start)}` : span.kind === 'open-start' ? formatTimeIn(span.start, tz) : formatTimeIn(span.end, tz)
  const sub = !span ? 'Chưa có lịch' : span.kind === 'window' ? `đến ${formatTimeIn(span.end, tz)}${tag(span.end)}` : span.kind === 'open-start' ? 'mở từ' : 'hạn đóng'
  const info = stateInfo(item.calendarState)
  return (
    <button type="button" className="cal-row" data-tone={info.tone} aria-current={selected} aria-label={summary(item, viewerTz)} onClick={() => onSelect(item)}>
      <span className="cal-time">{clock}<small>{sub}</small></span>
      <span>
        <span className="cal-title">{item.examTitle}</span>
        <span className="cal-meta" style={{ display: 'block' }}>{w.primary}{w.zone && <> · {w.zone}</>}</span>
        {w.mine && <span className="cal-meta" style={{ display: 'block' }}>{w.mine}</span>}
        <span className="cal-meta" style={{ display: 'block' }}>{cardMetrics(item, nowMs).join(' · ')}</span>
      </span>
      <span className="cal-state"><StateBadge state={item.calendarState} /></span>
    </button>
  )
}

export function MonthChip({ item, viewerTz, selected, onSelect }: { item: CalendarItem; viewerTz: string; selected: boolean; onSelect: (item: CalendarItem) => void }) {
  const span = itemSpan(item)
  const info = stateInfo(item.calendarState)
  return (
    <button type="button" className="cal-chip" data-tone={info.tone} aria-current={selected} aria-label={summary(item, viewerTz)} title={`${item.examTitle} · ${info.label}`} onClick={() => onSelect(item)}>
      {span ? `${formatTimeIn(span.start, viewerTz)} ` : ''}{item.examTitle}
    </button>
  )
}

export function Legend() {
  const rows: [string, string][] = [
    ['success', 'Làm được / đang mở'], ['neutral', 'Sắp tới / đã xuất bản'], ['warning', 'Nháp / chờ (nét đứt)'], ['muted', 'Đã đóng / đã nộp'], ['danger', 'Đã lỡ / lỗi'],
  ]
  return (
    <ul className="cal-legend" aria-label="Chú giải trạng thái">
      {rows.map(([tone, label]) => <li key={tone}><span className="cal-swatch" data-tone={tone} aria-hidden />{label}</li>)}
    </ul>
  )
}

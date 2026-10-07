import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { CalendarPlus, ChevronLeft, ChevronRight } from 'lucide-react'
import './calendar.css'
import { VIEW_LABEL, dayHeading, shiftAnchor, type CalendarView } from './calendarModel'
import { AgendaView, EmptyCalendar, MonthView, TimeGridView } from './CalendarViews'
import { Legend } from './ExamCard'
import DetailPanel from './DetailPanel'
import ScheduleExamPicker from './ScheduleExamPicker'
import { useAgendaCalendar, useGridCalendar, useIsNarrow, useNow, useViewerZone } from './useCalendar'
import { audienceOf } from '../../components/layout/navigation'
import { ErrorState, LoadingState, Notice, PageHeader } from '../../components/ui'
import { useAuthStore } from '../../store/authStore'
import { instantToDay, zoneLabel } from '../../lib/zone'
import { PushPrompt } from '../notifications/PushControls'

const VIEWS: CalendarView[] = ['agenda', 'month', 'week', 'day']
const KEY = 'examini-calendar-view'
const isView = (v: string | null): v is CalendarView => !!v && (VIEWS as string[]).includes(v)
const readView = (): CalendarView => { try { const v = localStorage.getItem(KEY); return isView(v) ? v : 'agenda' } catch { return 'agenda' } }
const DAY = /^\d{4}-\d{2}-\d{2}$/

function rangeLabel(view: CalendarView, days: string[]) {
  const fmt = (d: string, o: Intl.DateTimeFormatOptions) => new Intl.DateTimeFormat('vi-VN', { timeZone: 'UTC', ...o }).format(new Date(`${d}T12:00:00Z`))
  if (view === 'month') { const t = fmt(`${days[Math.floor(days.length / 2)].slice(0, 7)}-15`, { month: 'long', year: 'numeric' }); return t.charAt(0).toUpperCase() + t.slice(1) }
  if (view === 'week') return `${fmt(days[0], { day: '2-digit', month: '2-digit' })} – ${fmt(days[6], { day: '2-digit', month: '2-digit', year: 'numeric' })}`
  const t = dayHeading(days[0]); return t.charAt(0).toUpperCase() + t.slice(1)
}

export default function CalendarPage() {
  const user = useAuthStore(s => s.user)
  const teacher = audienceOf(user) !== 'STUDENT'
  const tz = useViewerZone()
  const [params, setParams] = useSearchParams()
  const narrowGrid = useIsNarrow(767), narrowPanel = useIsNarrow(1100)
  const view: CalendarView = isView(params.get('view')) ? (params.get('view') as CalendarView) : readView()
  const today = instantToDay(Date.now(), tz)
  const anchor = DAY.test(params.get('date') ?? '') ? (params.get('date') as string) : today
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [selectedDay, setSelectedDay] = useState<string | null>(null)
  const [scheduling, setScheduling] = useState(false)

  const agenda = useAgendaCalendar(view === 'agenda')
  const grid = useGridCalendar(view === 'agenda' ? 'month' : view, anchor, view !== 'agenda')
  const data = view === 'agenda' ? agenda : grid
  const nowMs = useNow(data.clockOffset)
  const items = data.items
  const selected = useMemo(() => items.find(i => i.examId === selectedId) ?? null, [items, selectedId])

  useEffect(() => { document.title = `${teacher ? 'Lịch thi' : 'Lịch của tôi'} - EXAMINI` }, [teacher])
  const update = useCallback((patch: Record<string, string | null>) => {
    const next = new URLSearchParams(params)
    for (const [k, v] of Object.entries(patch)) { if (v) next.set(k, v); else next.delete(k) }
    setParams(next, { replace: true })
  }, [params, setParams])

  function setView(v: CalendarView) {
    try { localStorage.setItem(KEY, v) } catch { /* optional */ }
    setSelectedId(null); setSelectedDay(null)
    update({ view: v, date: v === 'agenda' ? null : anchor === today ? null : anchor })
  }
  const go = (dir: -1 | 1) => update({ date: shiftAnchor(view, anchor, dir) }) // month steps stay on day 1
  const select = (item: { examId: string }) => setSelectedId(item.examId)
  // Closing the panel puts focus back on the card that is currently selected (it may differ from the first opener).
  const closePanel = useCallback(() => {
    const card = document.querySelector<HTMLElement>('.cal-row[aria-current="true"], .cal-chip[aria-current="true"], .cal-block[aria-current="true"]')
    setSelectedId(null)
    requestAnimationFrame(() => card?.isConnected && card.focus())
  }, [])
  const openDay = (day: string) => { try { localStorage.setItem(KEY, 'day') } catch { /* optional */ } setSelectedId(null); update({ view: 'day', date: day }) }

  const days = grid.days
  const noScheduled = view !== 'agenda' && !grid.isLoading && !grid.isError && items.length === 0
  const title = teacher ? 'Lịch thi' : 'Lịch của tôi'

  return (
    <div className="stack">
      <PageHeader title={title}
        description={teacher ? 'Đề thi bạn sở hữu hoặc cộng tác. Giờ trên thẻ theo múi giờ của từng kỳ thi.' : 'Kỳ thi được giao, đã tham gia hoặc bạn theo dõi. Giờ trên thẻ theo múi giờ của từng kỳ thi.'}
        actions={teacher && <button type="button" className="btn btn-primary" onClick={() => setScheduling(true)}><CalendarPlus size={16} aria-hidden /> Đặt lịch cho đề</button>} />

      <PushPrompt />

      <div className="cal-toolbar">
        <div className="tabs" role="tablist" aria-label="Kiểu xem lịch" style={{ border: '1px solid #000' }}>
          {VIEWS.map(v => <button key={v} type="button" role="tab" id={`view-${v}`} className="tab" aria-selected={view === v} onClick={() => setView(v)}>{VIEW_LABEL[v]}</button>)}
        </div>
        {view !== 'agenda' && (
          <div className="row" style={{ gap: 12 }}>
            <div className="cal-nav" role="group" aria-label="Điều hướng khoảng thời gian">
              <button type="button" className="btn btn-secondary btn-icon" aria-label="Khoảng trước" onClick={() => go(-1)}><ChevronLeft size={18} /></button>
              <button type="button" className="btn btn-secondary" onClick={() => update({ date: null })}>Hôm nay</button>
              <button type="button" className="btn btn-secondary btn-icon" aria-label="Khoảng sau" onClick={() => go(1)}><ChevronRight size={18} /></button>
            </div>
            <h2 className="cal-range" aria-live="polite">{rangeLabel(view, days)}</h2>
          </div>
        )}
      </div>

      <div className="stack-sm">
        <Legend />
        <p className="xsmall muted">
          {view === 'agenda' ? `Agenda hiển thị từ 30 ngày trước đến gần 1 năm sau, kèm kỳ thi chưa có lịch. Giờ "của bạn" theo ${zoneLabel(nowMs, tz)} (${tz}).`
            : `Lưới xếp theo múi giờ máy bạn: ${zoneLabel(nowMs, tz)} (${tz}). Mỗi thẻ ghi giờ theo múi giờ của kỳ thi. Kỳ thi chưa có lịch chỉ hiện ở Agenda.`}
        </p>
      </div>

      {data.truncated && <Notice tone="warning">Có quá nhiều kỳ thi trong khoảng này nên chỉ hiển thị một phần. Thu hẹp khoảng thời gian để xem đủ.</Notice>}

      <div className="cal-layout" data-panel={selected ? 'open' : 'closed'}>
        <div role="tabpanel" aria-labelledby={`view-${view}`} style={{ minWidth: 0 }}>
          {data.isLoading ? <div className="card"><LoadingState rows={6} label="Đang tải lịch…" /></div>
            : data.isError ? <div className="card"><ErrorState error={data.error} onRetry={() => void data.refetch()} /></div>
            : view === 'agenda' ? (items.length === 0
                ? <div className="card"><div style={{ padding: 32, textAlign: 'center' }}><h3>Chưa có kỳ thi trong lịch</h3><p className="muted small" style={{ marginTop: 6 }}>{teacher ? 'Đặt lịch cho đề thi để chúng xuất hiện ở đây.' : 'Kỳ thi được giao, đã tham gia hoặc đang theo dõi sẽ hiện ở đây. Theo dõi kỳ thi công khai ở mục Kỳ thi của tôi.'}</p></div></div>
                : <AgendaView items={items} tz={tz} nowMs={nowMs} selectedId={selectedId} onSelect={select} />)
            : noScheduled && view !== 'month' ? <EmptyCalendar unscheduledHint />
            : view === 'month' ? <MonthView days={days} anchor={anchor} items={items} tz={tz} nowMs={nowMs} selectedId={selectedId} onSelect={select} narrow={narrowGrid}
                selectedDay={selectedDay} onSelectDay={setSelectedDay} onOpenDay={openDay} />
            : <TimeGridView days={days} items={items} tz={tz} nowMs={nowMs} selectedId={selectedId} onSelect={select} narrow={narrowGrid} onOpenDay={openDay} />}
          {data.isFetching && !data.isLoading && <p className="xsmall muted" role="status" style={{ marginTop: 6 }}>Đang cập nhật…</p>}
        </div>
        {selected && <DetailPanel item={selected} nowMs={nowMs} viewerTz={tz} narrow={narrowPanel} onClose={closePanel} />}
      </div>

      {scheduling && <ScheduleExamPicker onClose={() => setScheduling(false)} />}
    </div>
  )
}

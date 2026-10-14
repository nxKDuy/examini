import { useCallback, useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { ArrowLeft, ArrowRight, CalendarPlus } from 'lucide-react'
import './calendar.css'
import { shiftAnchor } from './calendarModel'
import { DayDialog, MonthView } from './CalendarViews'
import { AgendaRow, Legend } from './ExamCard'
import DetailPanel from './DetailPanel'
import ScheduleExamPicker from './ScheduleExamPicker'
import { useGridCalendar, useIsNarrow, useNow, useViewerZone } from './useCalendar'
import { audienceOf } from '../../components/layout/navigation'
import { ErrorState, LoadingState, Notice, PageHeader } from '../../components/ui'
import { useAuthStore } from '../../store/authStore'
import { instantToDay, zoneLabel } from '../../lib/zone'
import { PushPrompt } from '../notifications/PushControls'
import { t, useT, locale } from '../../lib/i18n'

const DAY = /^\d{4}-\d{2}-\d{2}$/

function monthLabel(anchor: string) {
  return new Intl.DateTimeFormat(locale(), { timeZone: 'UTC', month: 'long', year: 'numeric' }).format(new Date(`${anchor.slice(0, 7)}-15T12:00:00Z`)).toUpperCase()
}

/** One month calendar. A day opens a popup with that day's Exams; an Exam opens the detail panel. */
export default function CalendarPage() {
  useT()
  const user = useAuthStore(s => s.user)
  const teacher = audienceOf(user) !== 'STUDENT'
  const tz = useViewerZone()
  const [params, setParams] = useSearchParams()
  const narrowGrid = useIsNarrow(767), narrowPanel = useIsNarrow(1100)
  const today = instantToDay(Date.now(), tz)
  const anchor = DAY.test(params.get('date') ?? '') ? (params.get('date') as string) : today
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [openDay, setOpenDay] = useState<string | null>(null)
  const [scheduling, setScheduling] = useState(false)

  const data = useGridCalendar('month', anchor, true, true)
  const nowMs = useNow(data.clockOffset)
  const items = data.items
  const unscheduled = useMemo(() => items.filter(i => !i.startAt && !i.endAt), [items])
  const selected = useMemo(() => items.find(i => i.examId === selectedId) ?? null, [items, selectedId])
  const title = teacher ? t('Lịch thi') : t('Lịch của tôi')

  useEffect(() => { document.title = `${title} - EXAMINI` }, [title])
  const goTo = useCallback((date: string | null) => {
    const next = new URLSearchParams(params)
    next.delete('view')
    if (date) next.set('date', date); else next.delete('date')
    setParams(next, { replace: true })
    setSelectedId(null); setOpenDay(null)
  }, [params, setParams])
  const go = (dir: -1 | 1) => goTo(shiftAnchor('month', anchor, dir))
  // Closing the panel puts focus back on the bar that is currently selected.
  const closePanel = useCallback(() => {
    const card = document.querySelector<HTMLElement>('.cal-row[aria-current="true"], .cal-chip[aria-current="true"]')
    setSelectedId(null)
    requestAnimationFrame(() => card?.isConnected && card.focus())
  }, [])
  // From the day popup: close it, then show the Exam in the detail panel.
  const chooseFromDay = (item: { examId: string }) => { setOpenDay(null); setSelectedId(item.examId) }

  return (
    <div className="stack">
      <PageHeader title={title}
        description={teacher ? t('Đề thi bạn sở hữu hoặc cộng tác. Bấm vào một ngày để xem chi tiết.') : t('Kỳ thi được giao, đã tham gia hoặc bạn theo dõi. Bấm vào một ngày để xem chi tiết.')}
        actions={teacher && <button type="button" className="btn btn-primary" onClick={() => setScheduling(true)}><CalendarPlus size={16} aria-hidden /> {t('Đặt lịch cho đề')}</button>} />

      <PushPrompt />

      <div className="cal-layout" data-panel={selected ? 'open' : 'closed'}>
        <div style={{ minWidth: 0 }} className="stack-sm">
          <div className="cal-head">
            <button type="button" className="cal-arrow" aria-label={t('Tháng trước')} onClick={() => go(-1)}><ArrowLeft size={26} /></button>
            <div className="cal-head-title">
              <h2 className="cal-range" aria-live="polite">{monthLabel(anchor)}</h2>
              <button type="button" className="cal-today" onClick={() => goTo(null)} disabled={anchor.slice(0, 7) === today.slice(0, 7)}>{t('Hôm nay')}</button>
            </div>
            <button type="button" className="cal-arrow" aria-label={t('Tháng sau')} onClick={() => go(1)}><ArrowRight size={26} /></button>
          </div>

          {data.truncated && <Notice tone="warning">{t('Có quá nhiều kỳ thi trong tháng này nên chỉ hiển thị một phần.')}</Notice>}
          {data.isLoading ? <div className="card"><LoadingState rows={6} label={t('Đang tải lịch…')} /></div>
            : data.isError ? <div className="card"><ErrorState error={data.error} onRetry={() => void data.refetch()} /></div>
            : <MonthView days={data.days} anchor={anchor} items={items} tz={tz} nowMs={nowMs} selectedId={selectedId} onSelect={i => setSelectedId(i.examId)} narrow={narrowGrid} onOpenDay={setOpenDay} />}
          {data.isFetching && !data.isLoading && <p className="xsmall muted" role="status">{t('Đang cập nhật…')}</p>}

          <Legend />
          <p className="xsmall muted">{t('Lưới theo múi giờ máy bạn:')} {zoneLabel(nowMs, tz)} ({tz}{t('). Mỗi kỳ thi ghi giờ theo múi giờ của kỳ thi.')}</p>

          {unscheduled.length > 0 && (
            <section className="card cal-group" aria-labelledby="unscheduled-h">
              <h2 id="unscheduled-h">{t('Chưa có lịch')}<small>{unscheduled.length} {t('kỳ thi')}</small></h2>
              {unscheduled.map(i => <AgendaRow key={i.examId} item={i} nowMs={nowMs} viewerTz={tz} selected={selectedId === i.examId} onSelect={() => setSelectedId(i.examId)} />)}
            </section>
          )}
        </div>
        {selected && <DetailPanel item={selected} nowMs={nowMs} viewerTz={tz} narrow={narrowPanel} onClose={closePanel} />}
      </div>

      {openDay && <DayDialog day={openDay} items={items} tz={tz} nowMs={nowMs} onSelect={chooseFromDay} onClose={() => setOpenDay(null)} />}
      {scheduling && <ScheduleExamPicker onClose={() => setScheduling(false)} />}
    </div>
  )
}

import { useMemo } from 'react'
import './calendar.css'
import type { CalendarItem } from './calendarApi'
import { dayHeading, dayNumber, itemsOnDay, stateInfo } from './calendarModel'
import { AgendaRow, MonthChip } from './ExamCard'
import { Dialog } from '../../components/Dialog'
import { instantToDay, weekdayIndex } from '../../lib/zone'
import { t, useT } from '../../lib/i18n'

const DOW = ['T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'CN']
const isWeekend = (day: string) => weekdayIndex(day) >= 5
const CHIPS_PER_CELL = 2

/** One month on a ruled grid. Exams show as bars; the day number (or empty space in a cell) opens that day's details. */
export function MonthView({ days, anchor, items, tz, nowMs, selectedId, onSelect, narrow, onOpenDay }: {
  days: string[]; anchor: string; items: CalendarItem[]; tz: string; nowMs: number; selectedId: string | null
  onSelect: (item: CalendarItem) => void; narrow: boolean; onOpenDay: (day: string) => void
}) {
  useT()
  const today = instantToDay(nowMs, tz)
  const byDay = useMemo(() => new Map(days.map(d => [d, itemsOnDay(items, d, tz)])), [days, items, tz])
  const month = anchor.slice(0, 7)
  return (
    <div className="cal-month" role="grid" aria-label={t('Lịch tháng')}>
      {DOW.map(d => <div key={d} className="cal-dow" role="columnheader">{d}</div>)}
      {days.map(day => {
        const list = byDay.get(day) ?? []
        const shown = narrow ? [] : list.slice(0, CHIPS_PER_CELL)
        return (
          <div key={day} role="gridcell" className="cal-cell" data-today={day === today} data-outside={day.slice(0, 7) !== month} data-weekend={isWeekend(day)}>
            <button type="button" className="cal-daynum" aria-label={`${dayHeading(day)}${list.length ? `, ${list.length} kỳ thi` : ''}. Xem chi tiết ngày`} onClick={() => onOpenDay(day)}>{dayNumber(day)}</button>
            {narrow
              ? list.length > 0 && <div className="cal-dots" aria-hidden>{list.slice(0, 4).map(i => <span key={i.examId} className="cal-dot" data-tone={stateInfo(i.calendarState).tone} />)}</div>
              : <>
                  {shown.map(i => <MonthChip key={i.examId} item={i} viewerTz={tz} selected={selectedId === i.examId} onSelect={onSelect} />)}
                  {list.length > CHIPS_PER_CELL && <button type="button" className="cal-more" onClick={() => onOpenDay(day)}>+{list.length - CHIPS_PER_CELL} {t('khác')}</button>}
                </>}
          </div>
        )
      })}
    </div>
  )
}

/** Popup with everything on one day. Choosing an Exam hands over to the detail panel. */
export function DayDialog({ day, items, tz, nowMs, onSelect, onClose }: {
  day: string; items: CalendarItem[]; tz: string; nowMs: number; onSelect: (item: CalendarItem) => void; onClose: () => void
}) {
  useT()
  const list = useMemo(() => itemsOnDay(items, day, tz), [items, day, tz])
  return (
    <Dialog title={dayHeading(day).replace(/^./, c => c.toUpperCase())} description={list.length ? t('{0} kỳ thi trong ngày. Chọn một kỳ thi để xem chi tiết.', { 0: list.length }) : undefined} onClose={onClose} size="lg">
      {list.length === 0
        ? <p className="small muted">{t('Không có kỳ thi trong ngày này.')}</p>
        : <div className="cal-daylist">{list.map(i => <AgendaRow key={i.examId} item={i} nowMs={nowMs} viewerTz={tz} selected={false} onSelect={onSelect} />)}</div>}
    </Dialog>
  )
}

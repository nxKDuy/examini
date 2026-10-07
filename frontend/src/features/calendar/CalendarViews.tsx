import { useEffect, useMemo, useRef } from 'react'
import './calendar.css'
import type { CalendarItem } from './calendarApi'
import { agendaGroups, collapseCrowded, dayHeading, dayNumber, itemSpan, itemsOnDay, layoutDay, stateInfo } from './calendarModel'
import { AgendaRow, MonthChip } from './ExamCard'
import { EmptyState } from '../../components/ui'
import { addDays, dayStart, instantToDay, zoneLabel, formatTimeIn, weekdayIndex } from '../../lib/zone'

const DOW = ['T2', 'T3', 'T4', 'T5', 'T6', 'T7', 'CN']
const shortDow = (day: string) => DOW[weekdayIndex(day)]
const isWeekend = (day: string) => weekdayIndex(day) >= 5

interface Common { items: CalendarItem[]; tz: string; nowMs: number; selectedId: string | null; onSelect: (item: CalendarItem) => void }

export function AgendaView({ items, tz, nowMs, selectedId, onSelect }: Common) {
  const groups = useMemo(() => agendaGroups(items, nowMs, tz), [items, nowMs, tz])
  const visible = groups.filter(g => g.items.length > 0 || g.key === 'today')
  return (
    <div className="stack">
      {visible.map(g => (
        <section key={g.key} className="card cal-group" aria-labelledby={`agenda-${g.key}`}>
          <h2 id={`agenda-${g.key}`}>{g.label}<small>{g.items.length} kỳ thi</small></h2>
          {g.items.length === 0
            ? <p className="small muted" style={{ padding: '14px 18px' }}>Hôm nay không có kỳ thi nào.</p>
            : <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                {g.items.map(item => <li key={item.examId}><AgendaRow item={item} nowMs={nowMs} viewerTz={tz} selected={selectedId === item.examId} onSelect={onSelect} /></li>)}
              </ul>}
        </section>
      ))}
    </div>
  )
}

export function MonthView({ days, anchor, items, tz, nowMs, selectedId, onSelect, narrow, selectedDay, onSelectDay, onOpenDay }: Common & {
  days: string[]; anchor: string; narrow: boolean; selectedDay: string | null; onSelectDay: (day: string) => void; onOpenDay: (day: string) => void
}) {
  const today = instantToDay(nowMs, tz)
  const byDay = useMemo(() => new Map(days.map(d => [d, itemsOnDay(items, d, tz)])), [days, items, tz])
  const month = anchor.slice(0, 7)
  const picked = narrow ? (selectedDay ?? (days.includes(today) ? today : anchor)) : null
  return (
    <div className="stack">
      <div className="cal-month" role="grid" aria-label="Lịch tháng">
        {DOW.map(d => <div key={d} className="cal-dow" role="columnheader">{d}</div>)}
        {days.map(day => {
          const list = byDay.get(day) ?? []
          const shown = narrow ? [] : list.slice(0, 3)
          return (
            <div key={day} role="gridcell" className="cal-cell" data-today={day === today} data-outside={day.slice(0, 7) !== month} data-weekend={isWeekend(day)} data-selected={picked === day}>
              <button type="button" className="cal-daynum" aria-label={`${dayHeading(day)}${list.length ? `, ${list.length} kỳ thi` : ''}`} onClick={() => (narrow ? onSelectDay(day) : onOpenDay(day))}>{dayNumber(day)}</button>
              {narrow
                ? list.length > 0 && <div className="cal-dots" aria-hidden>{list.slice(0, 4).map(i => <span key={i.examId} className="cal-dot" data-tone={stateInfo(i.calendarState).tone} />)}</div>
                : <>
                    {shown.map(i => <MonthChip key={i.examId} item={i} viewerTz={tz} selected={selectedId === i.examId} onSelect={onSelect} />)}
                    {list.length > 3 && <button type="button" className="cal-more" onClick={() => onOpenDay(day)}>+{list.length - 3} khác</button>}
                  </>}
            </div>
          )
        })}
      </div>
      {picked && (
        <section className="card cal-daylist" data-today={picked === today} aria-label={`Kỳ thi ngày ${dayHeading(picked)}`}>
          <h3>{dayHeading(picked)}</h3>
          {(byDay.get(picked) ?? []).length === 0
            ? <p className="small muted" style={{ padding: '14px 16px' }}>Không có kỳ thi trong ngày này.</p>
            : (byDay.get(picked) ?? []).map(i => <AgendaRow key={i.examId} item={i} nowMs={nowMs} viewerTz={tz} selected={selectedId === i.examId} onSelect={onSelect} />)}
        </section>
      )}
    </div>
  )
}

export function TimeGridView({ days, items, tz, nowMs, selectedId, onSelect, narrow, onOpenDay }: Common & { days: string[]; narrow: boolean; onOpenDay: (day: string) => void }) {
  const maxCols = days.length === 1 ? 6 : 3
  const today = instantToDay(nowMs, tz)
  const scroller = useRef<HTMLDivElement>(null)
  const firstDay = days[0]
  const blocksByDay = useMemo(() => new Map(days.map(d => [d, layoutDay(itemsOnDay(items, d, tz), d, tz)])), [days, items, tz])
  // Open the grid at the earliest Exam of the range (an hour of context above it); 07:00 when the range is empty.
  const earliestHour = useMemo(() => {
    const tops = [...blocksByDay.values()].flat().map(b => b.top)
    return tops.length ? Math.max(0, Math.floor((Math.min(...tops) / 100) * 24) - 1) : 7
  }, [blocksByDay])
  useEffect(() => {
    if (scroller.current) scroller.current.scrollTop = earliestHour * 52
  }, [firstDay, days.length, narrow, earliestHour])

  if (narrow) {
    return (
      <div className="stack">
        {days.map(day => {
          const list = itemsOnDay(items, day, tz)
          return (
            <section key={day} className="card cal-daylist" data-today={day === today} aria-label={dayHeading(day)}>
              <h3>{dayHeading(day)}</h3>
              {list.length === 0
                ? <p className="small muted" style={{ padding: '12px 16px' }}>Không có kỳ thi.</p>
                : list.map(i => <AgendaRow key={i.examId} item={i} nowMs={nowMs} viewerTz={tz} selected={selectedId === i.examId} onSelect={onSelect} />)}
            </section>
          )
        })}
      </div>
    )
  }

  const hours = Array.from({ length: 24 }, (_, h) => h)
  return (
    <div className="cal-grid" style={{ ['--cols' as string]: days.length }} role="grid" aria-label={days.length === 1 ? 'Lịch ngày' : 'Lịch tuần'}>
      <div className="cal-grid-head" role="row">
        <div />
        {days.map(day => <div key={day} role="columnheader" data-today={day === today}>{shortDow(day)} <span className="cal-headday">{dayNumber(day)}</span>/{day.slice(5, 7)}</div>)}
      </div>
      <div className="cal-scroll" ref={scroller}>
        <div className="cal-body">
          <div className="cal-hours" aria-hidden>{hours.map(h => <div key={h}>{String(h).padStart(2, '0')}:00</div>)}</div>
          {days.map(day => {
            const { shown: blocks, overflow } = collapseCrowded(blocksByDay.get(day) ?? [], maxCols)
            const from = dayStart(day, tz), total = dayStart(addDays(day, 1), tz) - from
            return (
              <div key={day} className="cal-col" role="gridcell" data-weekend={isWeekend(day)}>
                {blocks.map(b => {
                  const span = itemSpan(b.item)!, info = stateInfo(b.item.calendarState), etz = b.item.timezone
                  const range = span.kind === 'window' ? `${formatTimeIn(span.start, etz)}–${formatTimeIn(span.end, etz)}` : span.kind === 'open-start' ? `từ ${formatTimeIn(span.start, etz)}` : `đóng ${formatTimeIn(span.end, etz)}`
                  return (
                    <button key={b.item.examId} type="button" className="cal-block" data-tone={info.tone} aria-current={selectedId === b.item.examId}
                      aria-label={`${b.item.examTitle}, ${info.label}, ${range} ${zoneLabel(span.start, etz)}`}
                      style={{ top: `${b.top}%`, height: `${b.height}%`, left: `calc(${(b.col / b.cols) * 100}% + 1px)`, width: `calc(${100 / b.cols}% - 3px)` }} onClick={() => onSelect(b.item)}>
                      <strong>{b.item.examTitle}</strong>{range} {zoneLabel(span.start, etz)}
                    </button>
                  )
                })}
                {overflow.map(o => (
                  <button key={`more-${o.cluster}`} type="button" className="cal-block cal-more-block" aria-label={`Thêm ${o.hidden.length} kỳ thi cùng khung giờ, mở xem theo ngày`}
                    style={{ top: `${o.top}%`, height: `${o.height}%`, left: `calc(${(o.col / o.cols) * 100}% + 1px)`, width: `calc(${100 / o.cols}% - 3px)` }}
                    onClick={() => (days.length === 1 && o.hidden[0] ? onSelect(o.hidden[0]) : onOpenDay(day))}>
                    <strong>+{o.hidden.length} kỳ thi</strong>{days.length === 1 ? 'chọn từng đề' : 'xem theo ngày'}
                  </button>
                ))}
                {day === today && <div className="cal-now" style={{ top: `${((nowMs - from) / total) * 100}%` }} aria-hidden />}
              </div>
            )
          })}
        </div>
      </div>
    </div>
  )
}

export const EmptyCalendar = ({ unscheduledHint }: { unscheduledHint?: boolean }) => (
  <div className="card">
    <EmptyState title="Chưa có kỳ thi trong khoảng này" description={unscheduledHint ? 'Kỳ thi chưa có lịch chỉ hiện ở Agenda; Tháng/Tuần/Ngày không bịa ngày cho chúng.' : 'Thử chuyển sang khoảng thời gian khác hoặc xem Agenda.'} />
  </div>
)

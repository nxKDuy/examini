import { Link } from 'react-router-dom'
import { ArrowRight } from 'lucide-react'
import './calendar.css'
import { agendaGroups, cardMetrics, isTeacherItem, windowText } from './calendarModel'
import { StateBadge } from './ExamCard'
import { useAgendaCalendar, useNow } from './useCalendar'
import { EmptyState, ErrorState, LoadingState, Section } from '../../components/ui'
import { formatTimeIn } from '../../lib/zone'
import { itemSpan } from './calendarModel'

/** Dashboard block shared by Teacher and Student: today's Exams and the next 7 days, straight from the calendar API. */
export default function TodayUpcoming() {
  const q = useAgendaCalendar()
  const nowMs = useNow(q.clockOffset, 30_000)
  const groups = agendaGroups(q.items, nowMs, q.tz)
  const today = groups.find(g => g.key === 'today')!.items, upcoming = groups.find(g => g.key === 'upcoming')!.items
  const row = (i: (typeof today)[number]) => {
    const span = itemSpan(i), w = windowText(i, q.tz)
    const to = isTeacherItem(i) ? `/exams/${i.examId}` : `/exam/lobby/${i.examId}`
    return (
      <Link key={i.examId} to={to} className="list-item" style={{ textDecoration: 'none' }}>
        <div className="spread">
          <span><strong style={{ color: '#000', fontWeight: 400, fontSize: '1.05rem' }}>{i.examTitle}</strong></span>
          <StateBadge state={i.calendarState} />
        </div>
        <div className="xsmall muted">{span ? `${formatTimeIn(span.start, i.timezone)} · ` : ''}{w.primary}{w.zone && <> · {w.zone}</>}</div>
        <div className="xsmall muted">{cardMetrics(i, nowMs).join(' · ')}</div>
      </Link>
    )
  }
  return (
    <div className="grid-2">
      <Section title="Hôm nay" description={q.data ? `${today.length} kỳ thi` : undefined}
        actions={<Link to="/calendar" className="btn btn-ghost btn-sm">Mở Lịch <ArrowRight size={14} aria-hidden /></Link>} bodyClassName="">
        {q.isLoading ? <LoadingState rows={2} /> : q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} />
          : today.length === 0 ? <EmptyState title="Hôm nay không có kỳ thi" description="Kỳ thi có lịch trong ngày sẽ hiện ở đây." /> : today.slice(0, 5).map(row)}
      </Section>
      <Section title="Sắp tới" description="7 ngày tới" actions={<Link to="/calendar" className="btn btn-ghost btn-sm">Xem tất cả <ArrowRight size={14} aria-hidden /></Link>} bodyClassName="">
        {q.isLoading ? <LoadingState rows={2} /> : q.isError ? <ErrorState error={q.error} onRetry={() => void q.refetch()} />
          : upcoming.length === 0 ? <EmptyState title="Chưa có kỳ thi sắp tới" description="Không có kỳ thi nào trong 7 ngày tới." /> : upcoming.slice(0, 5).map(row)}
      </Section>
    </div>
  )
}

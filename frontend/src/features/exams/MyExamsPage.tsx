import { Link } from 'react-router-dom'
import { useQueries, useQuery } from '@tanstack/react-query'
import { Globe2, ListChecks } from 'lucide-react'
import { examApi } from './examApi'
import JoinCodeForm from './JoinCodeForm'
import { calendarApi, type CalendarItem } from '../calendar/calendarApi'
import { cardMetrics, itemSpan, windowText } from '../calendar/calendarModel'
import { StateBadge } from '../calendar/ExamCard'
import FollowButton from '../calendar/FollowButton'
import { useAgendaCalendar, useNow } from '../calendar/useCalendar'
import { EmptyState, ErrorState, LoadingState, PageHeader, Section } from '../../components/ui'
import { reasonText } from '../calendar/calendarModel'
import { t, useT } from '../../lib/i18n'

const RANK: Record<string, number> = { IN_PROGRESS: 0, AVAILABLE: 1, UPCOMING: 2, JOIN_REQUIRED: 2, NOT_OPEN: 3, MISSED: 5, CLOSED: 6, SUBMITTED: 6, AUTO_SUBMITTED: 6 }

export default function MyExamsPage() {
  useT()
  const my = useQuery({ queryKey: ['my-exams'], queryFn: examApi.my })
  const pub = useQuery({ queryKey: ['public-exams'], queryFn: examApi.publicExams })
  const agenda = useAgendaCalendar()
  const nowMs = useNow(agenda.clockOffset, 30_000)
  const byId = new Map<string, CalendarItem>(agenda.items.map(i => [i.examId, i]))
  const joined = new Set((my.data ?? []).map(e => e.id))
  const viewerTz = agenda.tz
  // Schedule, follow state and join eligibility of public Exams come from the availability endpoint, not from guessing.
  const publicList = (pub.data ?? []).slice(0, 20)
  const publicAvail = useQueries({ queries: publicList.map(e => ({ queryKey: ['availability', e.id], queryFn: () => calendarApi.availability(e.id), staleTime: 30_000, retry: false })) })

  const sorted = [...(my.data ?? [])].sort((a, b) => {
    const ia = byId.get(a.id), ib = byId.get(b.id)
    return (RANK[ia?.calendarState ?? ''] ?? 4) - (RANK[ib?.calendarState ?? ''] ?? 4) || (itemSpan(ia ?? { startAt: null, endAt: null })?.start ?? Infinity) - (itemSpan(ib ?? { startAt: null, endAt: null })?.start ?? Infinity)
  })

  return (
    <div className="stack-lg">
      <PageHeader title={t('Kỳ thi của tôi')} description={t('Kỳ thi bạn được phân công hoặc đã tham gia, kỳ thi công khai và tham gia bằng mã. Xem theo ngày ở mục Lịch của tôi.')}
        actions={<Link to="/calendar" className="btn btn-secondary">{t('Mở Lịch của tôi')}</Link>} />
      <div className="grid-2">
        <Section title={t('Tham gia bằng mã')}><JoinCodeForm /></Section>
        <Section title={t('Kỳ thi công khai')} description={t('Dành cho người dùng EXAMINI đã đăng nhập; không có truy cập ẩn danh. Theo dõi để đưa vào Lịch và nhận thông báo mà chưa cần tham gia.')} bodyClassName="">
          {pub.isLoading ? <LoadingState rows={2} /> : pub.isError ? <ErrorState error={pub.error} onRetry={() => void pub.refetch()} />
            : publicList.length === 0 ? <EmptyState icon={<Globe2 size={28} />} title={t('Không có kỳ thi công khai đang mở')} />
            : publicList.map((e, i) => {
              const a = publicAvail[i]?.data
              const w = a ? windowText(a, viewerTz) : null
              const isJoined = joined.has(e.id) || !!a?.participant
              return (
                <div key={e.id} className="spread" style={{ padding: '12px 20px', borderBottom: '1px solid #000', alignItems: 'flex-start' }}>
                  <div style={{ minWidth: 0, flex: 1 }}>
                    <div className="row"><strong style={{ color: '#000', fontWeight: 400, fontSize: '1.05rem' }}>{e.title}</strong>{a && <StateBadge state={a.calendarState} />}</div>
                    <div className="xsmall muted">{e.durationMinutes} {t('phút ·')} {e.questionIds.length} {t('câu')}{w && <> · {w.primary}{w.zone && <> · {w.zone}</>}</>}</div>
                    {w?.mine && <div className="xsmall muted">{w.mine}</div>}
                    {a && !a.canJoin && !isJoined && <div className="xsmall muted">{reasonText(a.reasonCode)}</div>}
                  </div>
                  <div className="row" style={{ flexShrink: 0 }}>
                    {a && !isJoined && <FollowButton examId={e.id} followed={a.followed} />}
                    <Link to={`/exam/lobby/${e.id}`} className="btn btn-secondary btn-sm">{isJoined ? t('Vào phòng chờ') : t('Xem trước')}</Link>
                  </div>
                </div>
              )
            })}
        </Section>
      </div>

      <Section title={t('Kỳ thi đã tham gia hoặc được giao')} bodyClassName="">
        {my.isLoading ? <LoadingState rows={3} /> : my.isError ? <ErrorState error={my.error} onRetry={() => void my.refetch()} />
          : sorted.length === 0 ? <EmptyState icon={<ListChecks size={30} />} title={t('Chưa có kỳ thi nào')} description={t('Kỳ thi được giáo viên phân công hoặc bạn đã tham gia sẽ xuất hiện ở đây.')} />
          : sorted.map(e => {
            const item = byId.get(e.id)
            const w = item ? windowText(item, viewerTz) : null
            return (
              <Link key={e.id} to={`/exam/lobby/${e.id}`} className="list-item" style={{ textDecoration: 'none' }}>
                <div className="spread">
                  <strong style={{ color: '#000', fontWeight: 400, fontSize: '1.1rem' }}>{e.title}</strong>
                  {item && <StateBadge state={item.calendarState} />}
                </div>
                <div className="xsmall muted" style={{ marginTop: 4 }}>
                  {item ? cardMetrics(item, nowMs).join(' · ') : t('{0} phút · {1} câu · {2} điểm', { 0: e.durationMinutes, 1: e.questionIds.length, 2: e.totalScore ?? 0 })}
                  {w && <> · {w.primary}{w.zone && <> · {w.zone}</>}</>}
                </div>
                {w?.mine && <div className="xsmall muted">{w.mine}</div>}
              </Link>
            )
          })}
      </Section>
    </div>
  )
}

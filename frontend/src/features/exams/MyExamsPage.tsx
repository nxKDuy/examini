import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query'
import { Globe2, ListChecks } from 'lucide-react'
import { availability, examApi, type Exam } from './examApi'
import { attemptsFor } from './attemptHints'
import { useAuthStore } from '../../store/authStore'
import JoinCodeForm from './JoinCodeForm'
import { AttemptBadge, AvailabilityBadge } from './StudentExamParts'
import { EmptyState, ErrorState, LoadingState, Notice, PageHeader, Section } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { ACCESS_MODE_LABEL, formatDateTime, formatScore } from '../../lib/format'

export default function MyExamsPage() {
  const user = useAuthStore(s => s.user)
  const qc = useQueryClient()
  const navigate = useNavigate()
  const [joinError, setJoinError] = useState('')
  const my = useQuery({ queryKey: ['my-exams'], queryFn: examApi.my })
  const pub = useQuery({ queryKey: ['public-exams'], queryFn: examApi.publicExams })
  const hints = attemptsFor(user?.id)
  const hinted = (my.data ?? []).filter(e => hints[e.id])
  const sessions = useQueries({ queries: hinted.map(e => ({ queryKey: ['session', hints[e.id]], queryFn: () => examApi.session(hints[e.id]), retry: false })) })
  const attemptState = Object.fromEntries(hinted.map((e, i) => [e.id, sessions[i]?.data?.status]))
  const joined = new Set((my.data ?? []).map(e => e.id))

  const join = useMutation({
    mutationFn: examApi.join,
    onSuccess: async exam => { await qc.invalidateQueries({ queryKey: ['my-exams'] }); navigate(`/exam/lobby/${exam.id}`) },
    onError: e => setJoinError(errorText(e)),
  })

  const sorted = [...(my.data ?? [])].sort((a, b) => rank(a) - rank(b))

  return (
    <div className="stack-lg">
      <PageHeader title="Kỳ thi của tôi" description="Kỳ thi bạn được phân công hoặc đã tham gia, kỳ thi công khai và tham gia bằng mã." />
      <div className="grid-2">
        <Section title="Tham gia bằng mã"><JoinCodeForm /></Section>
        <Section title="Kỳ thi công khai" description="Dành cho người dùng EXAMINI đã đăng nhập; không có truy cập ẩn danh." bodyClassName="">
          {joinError && <div style={{ padding: '12px 20px 0' }}><Notice tone="danger" live>{joinError}</Notice></div>}
          {pub.isLoading ? <LoadingState rows={2} /> : pub.isError ? <ErrorState error={pub.error} onRetry={() => void pub.refetch()} />
            : pub.data!.length === 0 ? <EmptyState icon={<Globe2 size={28} />} title="Không có kỳ thi công khai đang mở" />
            : pub.data!.map(e => (
              <div key={e.id} className="spread" style={{ padding: '12px 20px', borderBottom: '1px solid var(--color-border)' }}>
                <div style={{ minWidth: 0 }}>
                  <strong style={{ color: 'var(--color-ink)' }}>{e.title}</strong>
                  <div className="xsmall muted">{e.durationMinutes} phút · {e.questionIds.length} câu</div>
                </div>
                {joined.has(e.id)
                  ? <Link to={`/exam/lobby/${e.id}`} className="btn btn-secondary btn-sm">Vào phòng chờ</Link>
                  : <button type="button" className="btn btn-primary btn-sm" disabled={join.isPending} onClick={() => { setJoinError(''); join.mutate(e.id) }}>Tham gia</button>}
              </div>
            ))}
        </Section>
      </div>

      <Section title="Kỳ thi đã tham gia hoặc được giao" bodyClassName="">
        {my.isLoading ? <LoadingState rows={3} /> : my.isError ? <ErrorState error={my.error} onRetry={() => void my.refetch()} />
          : sorted.length === 0 ? <EmptyState icon={<ListChecks size={30} />} title="Chưa có kỳ thi nào" description="Kỳ thi được giáo viên phân công hoặc bạn đã tham gia sẽ xuất hiện ở đây." />
          : sorted.map(e => (
            <Link key={e.id} to={`/exam/lobby/${e.id}`} className="list-item" style={{ textDecoration: 'none', color: 'inherit' }}>
              <div className="spread">
                <strong style={{ color: 'var(--color-ink)' }}>{e.title}</strong>
                <span className="row">
                  {attemptState[e.id] && <AttemptBadge active={attemptState[e.id] === 'IN_PROGRESS'} />}
                  <AvailabilityBadge exam={e} />
                </span>
              </div>
              <div className="xsmall muted" style={{ marginTop: 4 }}>
                {e.durationMinutes} phút · {e.questionIds.length} câu · {formatScore(e.totalScore)} điểm · {ACCESS_MODE_LABEL[e.accessMode]}
                {e.endTime && <> · Nhận lượt mới đến {formatDateTime(e.endTime)}</>}
              </div>
            </Link>
          ))}
      </Section>
    </div>
  )
}

const ORDER = { OPEN: 0, UPCOMING: 1, NOT_OPEN: 2, ENDED: 3, CLOSED: 4 }
const rank = (e: Exam) => ORDER[availability(e)]

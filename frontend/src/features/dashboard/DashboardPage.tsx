import { Link, useLocation } from 'react-router-dom'
import { useQueries, useQuery } from '@tanstack/react-query'
import { ArrowRight, BookOpenCheck, ClipboardCheck, FilePlus2, PlayCircle, ShieldCheck } from 'lucide-react'
import { useAuthStore } from '../../store/authStore'
import { audienceOf } from '../../components/layout/navigation'
import { EmptyState, ErrorState, LoadingState, Notice, PageHeader, Section, Stat } from '../../components/ui'
import { ExamStatusBadge } from '../../components/status'
import { availability, examApi, type Exam } from '../exams/examApi'
import { attemptsFor } from '../exams/attemptHints'
import JoinCodeForm from '../exams/JoinCodeForm'
import { questionBankApi } from '../question-bank/questionApi'
import { gradingApi } from '../grading/gradingApi'
import { formatDateTime, formatDuration } from '../../lib/format'

export default function DashboardPage() {
  const user = useAuthStore(s => s.user)
  const audience = audienceOf(user)
  const location = useLocation()
  const denied = (location.state as { denied?: string } | null)?.denied
  const subtitle = audience === 'ADMIN' ? 'Quản trị hệ thống' : audience === 'TEACHER' ? 'Không gian giáo viên' : 'Không gian học sinh / sinh viên'
  return (
    <div className="stack-lg">
      <PageHeader title={`Xin chào, ${user?.fullName ?? ''}`} description={subtitle} />
      {denied && <Notice tone="warning" live>Tài khoản của bạn không có quyền truy cập trang {denied}. Bạn đã được chuyển về trang tổng quan.</Notice>}
      {audience === 'STUDENT' ? <StudentDashboard /> : audience === 'TEACHER' ? <TeacherDashboard /> : <AdminDashboard />}
    </div>
  )
}

function useQuestionCounts(statuses: string[]) {
  return useQueries({
    queries: statuses.map(status => ({
      queryKey: ['questions', 'count', status],
      queryFn: () => questionBankApi.getQuestions({ status, page: 0, size: 1 }).then(p => p.totalElements),
    })),
  })
}

function StudentDashboard() {
  const user = useAuthStore(s => s.user)
  const my = useQuery({ queryKey: ['my-exams'], queryFn: examApi.my })
  const pub = useQuery({ queryKey: ['public-exams'], queryFn: examApi.publicExams })
  const hints = Object.entries(attemptsFor(user?.id))
  const sessions = useQueries({
    queries: hints.map(([, sessionId]) => ({ queryKey: ['session', sessionId], queryFn: () => examApi.session(sessionId), retry: false })),
  })
  const active = sessions.map(s => s.data).filter(s => s?.status === 'IN_PROGRESS')
  const openForMe = (my.data ?? []).filter(e => availability(e) === 'OPEN')
  const joinedIds = new Set((my.data ?? []).map(e => e.id))
  const publicNew = (pub.data ?? []).filter(e => !joinedIds.has(e.id))

  return (
    <>
      {active.length > 0 && (
        <Notice tone="info" title="Bạn có lượt làm bài đang dở">
          <div className="stack-sm" style={{ marginTop: 6 }}>
            {active.map(s => s && (
              <div key={s.sessionId} className="spread">
                <span>{s.title}: còn khoảng {formatDuration((Date.parse(s.expiresAt) - Date.parse(s.serverNow)) / 1000)}</span>
                <Link to={`/exam/take/${s.sessionId}`} className="btn btn-primary btn-sm"><PlayCircle size={16} aria-hidden /> Tiếp tục làm bài</Link>
              </div>
            ))}
          </div>
        </Notice>
      )}
      <div className="grid-stats">
        <Stat label="Kỳ thi đã tham gia / được giao" value={my.data?.length ?? '-'} to="/my-exams" />
        <Stat label="Đang mở cho bạn" value={my.data ? openForMe.length : '-'} to="/my-exams" />
        <Stat label="Kỳ thi công khai đang mở" value={pub.data?.length ?? '-'} hint="Dành cho người dùng EXAMINI đã đăng nhập" to="/my-exams" />
      </div>
      <div className="grid-2">
        <Section title="Kỳ thi có thể làm ngay" actions={<Link to="/my-exams" className="btn btn-ghost btn-sm">Xem tất cả <ArrowRight size={14} aria-hidden /></Link>} bodyClassName="">
          {my.isLoading ? <LoadingState rows={2} /> : my.isError ? <ErrorState error={my.error} onRetry={() => void my.refetch()} />
            : openForMe.length === 0 ? <EmptyState title="Chưa có kỳ thi đang mở" description="Kỳ thi được giao hoặc đã tham gia sẽ xuất hiện ở đây khi giáo viên mở kỳ thi." />
            : <ExamLinks exams={openForMe} />}
        </Section>
        <Section title="Tham gia kỳ thi">
          <div className="stack">
            <JoinCodeForm compact />
            {publicNew.length > 0 && <p className="small muted">Có {publicNew.length} kỳ thi công khai bạn chưa tham gia. <Link to="/my-exams">Xem danh sách</Link></p>}
          </div>
        </Section>
      </div>
    </>
  )
}

function ExamLinks({ exams, to = (e: Exam) => `/exam/lobby/${e.id}` }: { exams: Exam[]; to?: (e: Exam) => string }) {
  return (
    <div>
      {exams.slice(0, 6).map(e => (
        <Link key={e.id} to={to(e)} className="list-item" style={{ textDecoration: 'none', color: 'inherit' }}>
          <div className="spread">
            <strong style={{ color: 'var(--color-ink)' }}>{e.title}</strong>
            <ExamStatusBadge status={e.status} />
          </div>
          <div className="xsmall muted" style={{ marginTop: 4 }}>{e.durationMinutes} phút · Kết thúc: {formatDateTime(e.endTime, 'không giới hạn')}</div>
        </Link>
      ))}
    </div>
  )
}

function TeacherDashboard() {
  const user = useAuthStore(s => s.user)
  const counts = useQuestionCounts(['DRAFT', 'PENDING_REVIEW', 'APPROVED', 'REJECTED'])
  const exams = useQuery({ queryKey: ['exams', 'list', 0], queryFn: () => examApi.list(0) })
  const invites = useQuery({ queryKey: ['invitations'], queryFn: examApi.invitations })
  const list = exams.data?.content ?? []
  const byStatus = (s: string) => list.filter(e => e.status === s).length
  const gradable = list.filter(e => e.status === 'OPEN' || e.status === 'CLOSED').slice(0, 8)
  const review = useQueries({
    queries: gradable.map(e => ({
      queryKey: ['submissions', 'count', e.id, 'NEEDS_REVIEW'],
      queryFn: () => gradingApi.getSubmissions({ examId: e.id, status: 'NEEDS_REVIEW', size: 1 }).then(p => p.totalElements),
      retry: false,
    })),
  })
  const needsReview = gradable.map((e, i) => ({ exam: e, count: review[i]?.data ?? 0 })).filter(x => x.count > 0)
  const c = (i: number) => counts[i]?.data ?? '-'

  return (
    <>
      {(invites.data?.length ?? 0) > 0 && (
        <Notice tone="info" title={`Bạn có ${invites.data!.length} lời mời cộng tác`} action={<Link to="/exams" className="btn btn-secondary btn-sm">Xem lời mời</Link>}>
          Chấp nhận lời mời để cùng quản lý hoặc giám sát kỳ thi.
        </Notice>
      )}
      <div className="grid-stats">
        <Stat label="Câu hỏi bản nháp" value={c(0)} to="/questions" />
        <Stat label="Đang chờ duyệt" value={c(1)} to="/questions" />
        <Stat label="Đã duyệt, dùng được" value={c(2)} to="/questions" />
        <Stat label="Bị từ chối, cần sửa" value={c(3)} to="/questions" />
      </div>
      <div className="grid-2">
        <Section title="Đề thi của tôi" description={exams.data ? `${exams.data.totalElements} đề thi bạn sở hữu hoặc cộng tác` : undefined}
          actions={<Link to="/exams/create" className="btn btn-primary btn-sm"><FilePlus2 size={16} aria-hidden /> Tạo đề thi</Link>} bodyClassName="">
          {exams.isLoading ? <LoadingState rows={3} /> : exams.isError ? <ErrorState error={exams.error} onRetry={() => void exams.refetch()} /> : list.length === 0
            ? <EmptyState icon={<FilePlus2 size={30} />} title="Chưa có đề thi" description="Tạo đề thi từ các câu hỏi đã được duyệt trong Ngân hàng câu hỏi." />
            : <>
              <div className="row small" style={{ padding: '12px 20px', gap: 16, borderBottom: '1px solid var(--color-border)' }}>
                <span>Bản nháp: <strong>{byStatus('DRAFT')}</strong></span>
                <span>Đã xuất bản: <strong>{byStatus('PUBLISHED')}</strong></span>
                <span>Đang mở: <strong>{byStatus('OPEN')}</strong></span>
                <span>Đã đóng: <strong>{byStatus('CLOSED')}</strong></span>
              </div>
              <ExamLinks exams={[...list.filter(e => e.status === 'OPEN'), ...list.filter(e => e.status !== 'OPEN')]} to={e => `/exams/${e.id}`} />
            </>}
        </Section>
        <Section title="Việc cần chấm" description="Bài có câu tự luận chờ giáo viên chấm" bodyClassName="">
          {exams.isLoading ? <LoadingState rows={2} /> : needsReview.length === 0
            ? <EmptyState icon={<ClipboardCheck size={30} />} title="Không có bài cần chấm tay"
                description={gradable.length ? 'Các kỳ thi đang mở hoặc đã đóng mà bạn quản lý chưa có bài cần chấm tay.' : 'Bài nộp xuất hiện sau khi kỳ thi được mở và thí sinh nộp bài.'} />
            : needsReview.map(({ exam, count }) => (
              <Link key={exam.id} to={`/grading?examId=${exam.id}&status=NEEDS_REVIEW`} className="list-item spread" style={{ textDecoration: 'none', color: 'inherit', display: 'flex' }}>
                <strong style={{ color: 'var(--color-ink)' }}>{exam.title}</strong>
                <span className="badge badge-warning">{count} bài cần chấm</span>
              </Link>
            ))}
        </Section>
      </div>
      <p className="xsmall muted">Số liệu lấy trực tiếp từ hệ thống cho tài khoản {user?.email}.</p>
    </>
  )
}

function AdminDashboard() {
  const counts = useQuestionCounts(['PENDING_REVIEW'])
  const pending = counts[0]
  return (
    <div className="grid-2">
      <Section title="Duyệt câu hỏi" description="Quản trị viên phê duyệt hoặc từ chối câu hỏi giáo viên gửi duyệt.">
        <div className="stack">
          {pending.isError ? <ErrorState error={pending.error} onRetry={() => void pending.refetch()} /> : (
            <div className="row" style={{ gap: 12 }}>
              <ShieldCheck size={28} aria-hidden style={{ color: 'var(--color-accent)' }} />
              <div>
                <div className="stat-value">{pending.data ?? '-'}</div>
                <div className="stat-label">câu hỏi đang chờ duyệt</div>
              </div>
            </div>
          )}
          <div><Link to="/questions" className="btn btn-primary"><BookOpenCheck size={16} aria-hidden /> Mở hàng chờ duyệt</Link></div>
        </div>
      </Section>
      <Section title="Phạm vi quản trị trong Phase 1">
        <ul className="small stack-sm" style={{ margin: 0, paddingLeft: 18 }}>
          <li>Duyệt câu hỏi: phê duyệt hoặc từ chối kèm lý do.</li>
          <li>Đề thi và chấm bài: chỉ với đề thi bạn sở hữu hoặc được mời cộng tác; quyền trên từng đề do máy chủ kiểm tra.</li>
          <li>Quản lý người dùng chưa có trong Phase 1.</li>
        </ul>
      </Section>
    </div>
  )
}

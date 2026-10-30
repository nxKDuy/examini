import { Link, useLocation } from 'react-router-dom'
import { progressApi } from '../progress/progressApi'
import { useQueries, useQuery } from '@tanstack/react-query'
import { BarChart3, BookOpenCheck, ClipboardCheck, FilePlus2, PlayCircle } from 'lucide-react'
import { formatScore } from '../../lib/format'
import { useAuthStore } from '../../store/authStore'
import { audienceOf } from '../../components/layout/navigation'
import { EmptyState, ErrorState, LoadingState, Notice, PageHeader, Section, Stat } from '../../components/ui'
import { ExamStatusBadge } from '../../components/status'
import { examApi, type Exam } from '../exams/examApi'
import TodayUpcoming from '../calendar/TodayUpcoming'
import { InvitationNotice } from '../organizations/MyInvitations'
import { useAgendaCalendar, useNow } from '../calendar/useCalendar'
import { countdownLine } from '../calendar/calendarModel'
import JoinCodeForm from '../exams/JoinCodeForm'
import { questionBankApi } from '../question-bank/questionApi'
import { gradingApi } from '../grading/gradingApi'
import { t, useT } from '../../lib/i18n'

export default function DashboardPage() {
  useT()
  const user = useAuthStore(s => s.user)
  const audience = audienceOf(user)
  const location = useLocation()
  const denied = (location.state as { denied?: string } | null)?.denied
  const subtitle = audience === 'ADMIN' ? t('Quản trị hệ thống') : audience === 'TEACHER' ? t('Không gian giáo viên') : t('Không gian học sinh / sinh viên')
  return (
    <div className="stack-lg">
      <PageHeader title={t('Xin chào, {0}', { 0: user?.fullName ?? '' })} description={subtitle} />
      {denied && <Notice tone="warning" live>{t('Tài khoản của bạn không có quyền truy cập trang')} {denied}{t('. Bạn đã được chuyển về trang tổng quan.')}</Notice>}
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
  useT()
  const my = useQuery({ queryKey: ['my-exams'], queryFn: examApi.my })
  const pub = useQuery({ queryKey: ['public-exams'], queryFn: examApi.publicExams })
  const progress = useQuery({ queryKey: ['progress', 'mine'], queryFn: progressApi.mine, retry: false })
  const agenda = useAgendaCalendar()
  const nowMs = useNow(agenda.clockOffset, 30_000)
  // Availability and attempt state are the backend's answers (calendar items), not guesses from this browser's clock.
  const active = agenda.items.filter(i => i.calendarState === 'IN_PROGRESS' && i.latestSessionId)
  const doable = agenda.items.filter(i => i.calendarState === 'AVAILABLE' || i.calendarState === 'IN_PROGRESS')
  const joinedIds = new Set((my.data ?? []).map(e => e.id))
  const publicNew = (pub.data ?? []).filter(e => !joinedIds.has(e.id))

  return (
    <>
      {active.length > 0 && (
        <Notice tone="info" title={t('Bạn có lượt làm bài đang dở')}>
          <div className="stack-sm" style={{ marginTop: 6 }}>
            {active.map(i => (
              <div key={i.examId} className="spread">
                <span>{i.examTitle}: {countdownLine(i, nowMs) ?? t('đang làm')}</span>
                <Link to={`/exam/take/${i.latestSessionId}`} className="btn btn-primary btn-sm"><PlayCircle size={16} aria-hidden /> {t('Tiếp tục làm bài')}</Link>
              </div>
            ))}
          </div>
        </Notice>
      )}
      <InvitationNotice />
      <div className="grid-stats">
        <Stat label={t('Kỳ thi đã tham gia / được giao')} value={my.data?.length ?? '-'} to="/my-exams" />
        <Stat label={t('Làm được ngay')} value={agenda.data ? doable.length : '-'} to="/calendar" />
        <Stat label={t('Kỳ thi công khai đang mở')} value={pub.data?.length ?? '-'} hint={t('Dành cho người dùng EXAMINI đã đăng nhập')} to="/my-exams" />
        <Stat label={t('Điểm trung bình của tôi')} value={progress.data?.overview.averagePercent == null ? '-' : `${formatScore(progress.data.overview.averagePercent)}%`} hint={t('Xem tiến bộ và gợi ý ôn tập')} to="/progress" />
      </div>
      <TodayUpcoming />
      <div className="grid-2">
        <Section title={t('Tham gia kỳ thi')}>
          <div className="stack">
            <JoinCodeForm compact />
            {publicNew.length > 0 && <p className="small muted">{t('Có')} {publicNew.length} {t('kỳ thi công khai bạn chưa tham gia.')} <Link to="/my-exams">{t('Xem và theo dõi')}</Link></p>}
          </div>
        </Section>
      </div>
    </>
  )
}

function ExamLinks({ exams, to = (e: Exam) => `/exam/lobby/${e.id}` }: { exams: Exam[]; to?: (e: Exam) => string }) {
  useT()
  return (
    <div>
      {exams.slice(0, 6).map(e => (
        <Link key={e.id} to={to(e)} className="list-item" style={{ textDecoration: 'none', color: 'inherit' }}>
          <div className="spread">
            <strong style={{ color: 'var(--color-ink)' }}>{e.title}</strong>
            <ExamStatusBadge status={e.status} />
          </div>
          <div className="xsmall muted" style={{ marginTop: 4 }}>{e.durationMinutes} {t('phút ·')} {e.questionIds.length} {t('câu')}</div>
        </Link>
      ))}
    </div>
  )
}

function TeacherDashboard() {
  useT()
  const user = useAuthStore(s => s.user)
  const counts = useQuestionCounts(['DRAFT', 'ACTIVE', 'ARCHIVED'])
  const exams = useQuery({ queryKey: ['exams', 'list', 0], queryFn: () => examApi.list(0) })
  const invites = useQuery({ queryKey: ['invitations'], queryFn: examApi.invitations })
  const list = exams.data?.content ?? []
  const byStatus = (s: string) => list.filter(e => e.status === s).length
  const gradable = list.filter(e => e.status === 'OPEN' || e.status === 'CLOSED').slice(0, 8)
  const review = useQueries({
    queries: gradable.map(e => ({
      queryKey: ['grading', 'overview', e.id, 'needs-review'],
      queryFn: () => gradingApi.overview(e.id).then(o => o.needsReview),
      retry: false,
    })),
  })
  const needsReview = gradable.map((e, i) => ({ exam: e, count: review[i]?.data ?? 0 })).filter(x => x.count > 0)
  const c = (i: number) => counts[i]?.data ?? '-'

  return (
    <>
      <InvitationNotice />
      {(invites.data?.length ?? 0) > 0 && (
        <Notice tone="info" title={t('Bạn có {0} lời mời cộng tác', { 0: invites.data!.length })} action={<Link to="/exams" className="btn btn-secondary btn-sm">{t('Xem lời mời')}</Link>}>
          {t('Chấp nhận lời mời để cùng quản lý hoặc giám sát kỳ thi.')}
        </Notice>
      )}
      <div className="grid-stats">
        <Stat label={t('Câu hỏi bản nháp')} value={c(0)} to="/questions" />
        <Stat label={t('Câu hỏi đang dùng')} value={c(1)} to="/questions" />
        <Stat label={t('Câu hỏi đã lưu trữ')} value={c(2)} to="/questions" />
      </div>
      <TodayUpcoming />
      <div className="grid-2">
        <Section title={t('Đề thi của tôi')} description={exams.data ? t('{0} đề thi bạn sở hữu hoặc cộng tác', { 0: exams.data.totalElements }) : undefined}
          actions={<Link to="/exams/create" className="btn btn-primary btn-sm"><FilePlus2 size={16} aria-hidden /> {t('Tạo đề thi')}</Link>} bodyClassName="">
          {exams.isLoading ? <LoadingState rows={3} /> : exams.isError ? <ErrorState error={exams.error} onRetry={() => void exams.refetch()} /> : list.length === 0
            ? <EmptyState icon={<FilePlus2 size={30} />} title={t('Chưa có đề thi')} description={t('Tạo đề thi từ các câu hỏi đủ điều kiện trong Ngân hàng câu hỏi.')} />
            : <>
              <div className="row small" style={{ padding: '12px 20px', gap: 16, borderBottom: '1px solid var(--color-border)' }}>
                <span>{t('Bản nháp:')} <strong>{byStatus('DRAFT')}</strong></span>
                <span>{t('Đã xuất bản:')} <strong>{byStatus('PUBLISHED')}</strong></span>
                <span>{t('Đang mở:')} <strong>{byStatus('OPEN')}</strong></span>
                <span>{t('Đã đóng:')} <strong>{byStatus('CLOSED')}</strong></span>
              </div>
              <ExamLinks exams={[...list.filter(e => e.status === 'OPEN'), ...list.filter(e => e.status !== 'OPEN')]} to={e => `/exams/${e.id}`} />
            </>}
        </Section>
        <Section title={t('Việc cần chấm')} description={t('Bài có câu tự luận chờ giáo viên chấm')} bodyClassName="">
          {exams.isLoading ? <LoadingState rows={2} /> : needsReview.length === 0
            ? <EmptyState icon={<ClipboardCheck size={30} />} title={t('Không có bài cần chấm tay')}
                description={gradable.length ? t('Các kỳ thi đang mở hoặc đã đóng mà bạn quản lý chưa có bài cần chấm tay.') : t('Bài nộp xuất hiện sau khi kỳ thi được mở và thí sinh nộp bài.')} />
            : needsReview.map(({ exam, count }) => (
              <Link key={exam.id} to={`/grading?examId=${exam.id}&status=NEEDS_REVIEW`} className="list-item spread" style={{ textDecoration: 'none', color: 'inherit', display: 'flex' }}>
                <strong style={{ color: 'var(--color-ink)' }}>{exam.title}</strong>
                <span className="badge badge-warning">{count} {t('bài cần chấm')}</span>
              </Link>
            ))}
        </Section>
      </div>
      <p className="xsmall muted">{t('Số liệu lấy trực tiếp từ hệ thống cho tài khoản')} {user?.email}.</p>
    </>
  )
}

function AdminDashboard() {
  useT()
  return <div className="grid-2">
    <Section title={t('Thống kê hệ thống')} description={t('Người dùng, đề thi, lượt thi và mức độ dùng AI trên toàn hệ thống. Chỉ số đếm, không có dữ liệu cá nhân.')}>
      <Link to="/admin/stats" className="btn btn-primary"><BarChart3 size={16} aria-hidden /> {t('Mở thống kê')}</Link>
    </Section>
    <Section title={t('Ngân hàng câu hỏi')} description={t('Câu cá nhân không cần duyệt. Duyệt đóng góp tại từng tổ chức theo quyền BANK_REVIEW.')}>
      <Link to="/questions" className="btn btn-primary"><BookOpenCheck size={16} aria-hidden /> {t('Mở câu hỏi')}</Link>
    </Section>
    <Section title={t('Phạm vi quản trị trong Phase 1')}>
      <p className="small">{t('Quyền hệ thống không thay quyền tổ chức. Đề thi và chấm bài theo quyền từng đề do máy chủ kiểm tra.')}</p>
    </Section>
  </div>
}

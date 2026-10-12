import { useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { CalendarClock, Check, ClipboardCheck, Lock, Pencil, PlayCircle, RefreshCw, RotateCcw, Send, Trash2, UserPlus } from 'lucide-react'
import { examApi, type Exam, type MemberRole, type Policy } from './examApi'
import type { Question } from '../question-bank/questionApi'
import { bankApi } from '../banks/bankApi'
import PolicyFields from './PolicyFields'
import { policyError } from './policy'
import { useAuthStore } from '../../store/authStore'
import { ExamStatusBadge, QuestionStatusBadge, SessionStatusBadge } from '../../components/status'
import { EXAM_STATUS, QUESTION_STATUS } from '../../components/statusDefs'
import { ConfirmDialog } from '../../components/Dialog'
import { calendarApi, type Schedule } from '../calendar/calendarApi'
import { gradingApi } from '../grading/gradingApi'
import ParticipantsSection from '../participants/ParticipantsSection'
import { ruleLabel, RULE_HELP, windowText, toMs } from '../calendar/calendarModel'
import { offsetLabel, normalizeReminders } from '../calendar/scheduleModel'
import ScheduleDialog from '../calendar/ScheduleDialog'
import LifecycleDialog, { type LifecycleKind } from '../calendar/LifecycleDialog'
import ExamAnalyticsPanel from './ExamAnalyticsPanel'
import IntegrityTab from '../integrity/IntegrityTab'
import IntegrityLockControl from '../integrity/IntegrityLockControl'
import { policySummary } from '../integrity/integrityModel'
import { useNow, useViewerZone } from '../calendar/useCalendar'
import { Badge, EmptyState, ErrorState, Field, LoadingState, Notice, PageHeader, Section } from '../../components/ui'
import { UPSTREAM_GENERIC, errorInfo, errorText } from '../../lib/errors'
import { ACCESS_MODE_LABEL, EXAM_TYPE_LABEL, MEMBER_ROLE_LABEL, QUESTION_TYPE_LABEL, formatDateTime, formatScore, shortId } from '../../lib/format'

const LIFECYCLE = ['DRAFT', 'PUBLISHED', 'OPEN', 'CLOSED'] as const
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i
type Transition = 'publish' | 'open' | 'delete'
type Tab = 'overview' | 'schedule' | 'participation' | 'collaborators' | 'attempts' | 'integrity' | 'analytics' | 'audit'

export default function ExamDetailPage() {
  const { id } = useParams()
  const user = useAuthStore(s => s.user)
  const exam = useQuery({ queryKey: ['exam', id], queryFn: () => examApi.get(id!) })
  const members = useQuery({ queryKey: ['members', id], queryFn: () => examApi.members(id!), enabled: exam.isSuccess })
  if (exam.isLoading) return <LoadingState rows={6} />
  if (exam.isError || !exam.data) return <ErrorState error={exam.error} onRetry={() => void exam.refetch()} />
  const role = exam.data.ownerUserId === user?.id ? 'OWNER' : members.data?.[user?.id ?? ''] ?? (members.isLoading ? undefined : 'NONE')
  return <Detail exam={exam.data} role={role} members={members.data ?? {}} />
}

function Detail({ exam, role, members }: { exam: Exam; role?: string; members: Record<string, MemberRole> }) {
  const qc = useQueryClient()
  const actorId = useAuthStore(s => s.user?.id ?? '')
  const navigate = useNavigate()
  const location = useLocation()
  const flash = location.state as { notice?: string; tone?: 'success' | 'warning' } | null
  const [tab, setTab] = useState<Tab>('overview')
  const [confirm, setConfirm] = useState<Transition | null>(null)
  const [lifecycle, setLifecycle] = useState<LifecycleKind | null>(null)
  const [editing, setEditing] = useState(false)
  const viewerTz = useViewerZone()
  const schedule = useQuery({
    queryKey: ['schedule', exam.id],
    queryFn: async () => { const s = await calendarApi.getSchedule(exam.id); return { s, clockOffset: Date.parse(s.serverNow) - Date.now() } },
    staleTime: 15_000,
  })
  const nowMs = useNow(schedule.data?.clockOffset ?? 0, 15_000)
  const [message, setMessage] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const manage = role === 'OWNER' || role === 'MANAGER'
  const staff = manage || role === 'PROCTOR'
  const canLock = staff && (exam.status === 'PUBLISHED' || exam.status === 'OPEN' || !!exam.integrityLocked)
  // Badge on the Grading button: this Exam's own count of submissions that need a manual score (server-side overview, not a guess).
  const gradingOverview = useQuery({
    queryKey: ['grading', 'overview', exam.id], queryFn: () => gradingApi.overview(exam.id), retry: false, staleTime: 30_000,
    enabled: manage && (exam.status === 'OPEN' || exam.status === 'CLOSED'),
  })
  const gradingBadge = gradingOverview.data?.needsReview ?? 0
  const questions = useQuery({ queryKey: ['questions', 'draft-resources', exam.id, exam.status, exam.questionIds], queryFn: () => bankApi.eligibleBatch(exam.questionIds), enabled: exam.status === 'DRAFT' && exam.questionIds.length > 0, retry: false })
  const ineligible = (exam.status === 'DRAFT' ? questions.data ?? [] : []).map((q, i) => ({ n: i + 1, q })).filter(x => x.q.eligible !== true)
  const ineligibleText = ineligible.map(x => `Câu ${x.n} (${QUESTION_STATUS[x.q.status]?.label ?? x.q.status}, phiên bản ${x.q.questionVersion})`).join(', ')
  const invalidate = () => Promise.all([qc.invalidateQueries({ queryKey: ['exam', exam.id] }), qc.invalidateQueries({ queryKey: ['exams'] })])

  const transition = useMutation({
    mutationFn: async (kind: Transition): Promise<void> => { if (kind === 'delete') await examApi.remove(exam.id); else await examApi[kind](exam.id) },
    onSuccess: async (_r, kind) => {
      setConfirm(null)
      if (kind === 'delete') { await qc.invalidateQueries({ queryKey: ['exams'] }); navigate('/exams', { replace: true }); return }
      setMessage({ tone: 'success', text: { publish: 'Đã xuất bản. Nội dung và phiên bản câu hỏi đã được cố định.', open: 'Kỳ thi đã mở. Thí sinh đủ điều kiện có thể bắt đầu khi đến giờ bắt đầu.' }[kind as 'publish' | 'open'] })
      await invalidate()
    },
    onError: (e, kind) => {
      setConfirm(null)
      const info = errorInfo(e)
      const providerRejected = kind === 'publish' && info.status === 409 && UPSTREAM_GENERIC.test(info.detail ?? '')
      setMessage({ tone: 'danger', text: providerRejected
        ? `Ngân hàng câu hỏi từ chối xuất bản: có câu hỏi không còn đủ điều kiện hoặc không còn quyền sử dụng.${ineligibleText ? ` Kiểm tra: ${ineligibleText}.` : ''} Kiểm tra ngân hàng và quyền sử dụng hoặc thay câu hỏi rồi xuất bản lại.`
        : info.message })
      void qc.invalidateQueries({ queryKey: ['questions', 'draft-resources'] })
    },
  })

  const current = LIFECYCLE.indexOf(exam.status as typeof LIFECYCLE[number])
  const tabs: [Tab, string][] = [['overview', 'Tổng quan'], ['schedule', 'Lịch'], ['participation', 'Tham gia'], ['collaborators', 'Cộng tác'], ['attempts', 'Lượt làm bài'], ...(staff && exam.status !== 'DRAFT' ? [['integrity', 'Giám sát'] as [Tab, string]] : []), ...(manage ? [['analytics', 'Phân tích'] as [Tab, string]] : []), ['audit', 'Nhật ký']]

  return (
    <div className="stack-lg">
      <PageHeader title={exam.title} crumbs={[{ label: 'Đề thi', to: '/exams' }, { label: exam.title }]}
        description={<span className="row" style={{ gap: 8 }}><ExamStatusBadge status={exam.status} />
          {role && role !== 'NONE' && <Badge tone="muted">Vai trò: {MEMBER_ROLE_LABEL[role]}</Badge>}
          {exam.integrityLocked && <Badge tone="danger" icon={<Lock size={12} aria-hidden />}>Đã khóa toàn kỳ thi</Badge>}
          <span>Mã đề <strong className="mono">{exam.code}</strong></span></span>}
        actions={staff && <>
          {manage && <>
          {exam.status === 'DRAFT' && <>
            <button type="button" className="btn btn-danger-ghost" onClick={() => setConfirm('delete')}><Trash2 size={16} aria-hidden /> Xóa</button>
            <Link to={`/exams/${exam.id}/edit`} className="btn btn-secondary"><Pencil size={16} aria-hidden /> Sửa</Link>
            <button type="button" className="btn btn-primary" onClick={() => setConfirm('publish')}><Send size={16} aria-hidden /> Xuất bản</button>
          </>}
          {exam.status === 'PUBLISHED' && <button type="button" className="btn btn-primary" onClick={() => setConfirm('open')}><PlayCircle size={16} aria-hidden /> Mở kỳ thi</button>}
          {(exam.status === 'OPEN' || exam.status === 'PUBLISHED') && <button type="button" className="btn btn-danger" onClick={() => setLifecycle('close')}><Lock size={16} aria-hidden /> Đóng kỳ thi</button>}
          {exam.status === 'CLOSED' && <button type="button" className="btn btn-secondary" onClick={() => setLifecycle('reopen')}><RotateCcw size={16} aria-hidden /> Mở lại</button>}
          {(exam.status === 'OPEN' || exam.status === 'CLOSED') && <Link to={`/grading?examId=${exam.id}`} className="btn btn-secondary"><ClipboardCheck size={16} aria-hidden /> Chấm bài{gradingBadge > 0 && <span className="badge badge-warning" style={{ marginLeft: 6 }} aria-label={`${gradingBadge} bài cần chấm tay`}>{gradingBadge}</span>}</Link>}
          </>}
          {canLock && <IntegrityLockControl exam={exam} onDone={text => setMessage({ tone: 'success', text })} />}
        </>} />
      {exam.integrityLocked && <Notice tone="danger" title="Kỳ thi đang bị khóa">Không nhận lượt bắt đầu mới. Mở khóa không khôi phục các bài đã được nộp khi khóa.</Notice>}

      {flash?.notice && !message && <Notice tone={flash.tone ?? 'success'} live>{flash.notice}</Notice>}
      {exam.status === 'DRAFT' && questions.isError && <Notice tone="warning">Chưa xác minh được câu hỏi trong đề: {errorText(questions.error)}. Máy chủ kiểm tra lại khi xuất bản.</Notice>}
      {message && <Notice tone={message.tone} live title={message.tone === 'danger' ? 'Không thực hiện được' : undefined}>{message.text}</Notice>}
      {manage && ineligible.length > 0 && (
        <Notice tone="warning" title="Chưa thể xuất bản">
          {ineligible.length} câu hỏi hiện không đủ điều kiện: {ineligibleText}. Kiểm tra điều kiện ngân hàng hoặc thay câu hỏi.
        </Notice>
      )}
      {role === 'PROCTOR' && <Notice tone="info">Bạn là Giám thị: xem thông tin và lượt làm bài. Xuất bản, mở, đóng kỳ thi và chấm bài do Chủ sở hữu hoặc Quản lý thực hiện.</Notice>}

      <section className="card card-body stack" aria-label="Vòng đời kỳ thi">
        <ol className="steps">
          {LIFECYCLE.map((s, i) => (
            <li key={s} className="step" data-state={i < current ? 'done' : i === current ? 'current' : 'todo'} aria-current={i === current ? 'step' : undefined}>
              <span className="step-dot">{i < current ? <Check size={14} aria-hidden /> : i + 1}</span>{EXAM_STATUS[s].label}
              {i < LIFECYCLE.length - 1 && <span className="step-sep" aria-hidden style={{ marginLeft: 12 }} />}
            </li>
          ))}
        </ol>
        <p className="small">{EXAM_STATUS[exam.status]?.help}</p>
      </section>

      <div className="tabs" role="tablist" aria-label="Thông tin đề thi">
        {tabs.map(([value, label]) => (
          <button key={value} type="button" role="tab" id={`tab-${value}`} aria-controls={`panel-${value}`} className="tab" aria-selected={tab === value} onClick={() => setTab(value)}>{label}</button>
        ))}
      </div>
      <div role="tabpanel" id={`panel-${tab}`} aria-labelledby={`tab-${tab}`}>
        {tab === 'overview' && <Overview exam={exam} questions={exam.status === 'DRAFT' ? exam.questionIds.map(id => questions.data?.find(q => q.id === id)) : []} schedule={schedule.data?.s} viewerTz={viewerTz} onSchedule={() => setTab('schedule')} />}
        {tab === 'schedule' && <ScheduleTab exam={exam} manage={manage} schedule={schedule.data?.s} loading={schedule.isLoading} error={schedule.error} onRetry={() => void schedule.refetch()} viewerTz={viewerTz} onEdit={() => setEditing(true)} />}
        {tab === 'participation' && <Participation exam={exam} manage={manage} />}
        {tab === 'collaborators' && <Collaborators exam={exam} owner={role === 'OWNER'} members={members} />}
        {tab === 'attempts' && <Attempts exam={exam} />}
        {tab === 'integrity' && staff && <IntegrityTab examId={exam.id} policy={exam.integrityPolicy} manage={manage} live={exam.status === 'OPEN'} />}
        {tab === 'analytics' && manage && <ExamAnalyticsPanel examId={exam.id} actorId={actorId} />}
        {tab === 'audit' && <Audit exam={exam} />}
      </div>

      {confirm === 'publish' && <ConfirmDialog title="Xuất bản đề thi?" confirmLabel="Xuất bản" busy={transition.isPending}
        onConfirm={() => transition.mutate('publish')} onCancel={() => setConfirm(null)}>
        <ul className="small stack-sm" style={{ margin: 0, paddingLeft: 18 }}>
          <li>Hệ thống kiểm tra quyền và <strong>điều kiện sử dụng từng ngân hàng</strong> và cố định đúng phiên bản hiện tại cùng đáp án, điểm.</li>
          <li>Sau khi xuất bản, không thể sửa nội dung, câu hỏi, điểm hay chính sách tham gia của đề.</li>
          <li>Sửa câu hỏi trong Ngân hàng sau đó không làm thay đổi đề đã xuất bản.</li>
          <li>Thí sinh chỉ bắt đầu được sau khi bạn <strong>Mở kỳ thi</strong>.</li>
        </ul>
        {ineligible.length > 0 && <div style={{ marginTop: 12 }}><Notice tone="warning">Hiện có câu hỏi không đủ điều kiện ({ineligibleText}); máy chủ sẽ từ chối xuất bản.</Notice></div>}
      </ConfirmDialog>}
      {confirm === 'open' && <ConfirmDialog title="Mở kỳ thi?" confirmLabel="Mở kỳ thi" busy={transition.isPending}
        description={`Thí sinh đủ điều kiện (${ACCESS_MODE_LABEL[exam.accessMode]}) có thể bắt đầu làm bài${exam.startTime || exam.endTime ? ' trong khung giờ đã đặt' : ''}. Kỳ thi đang mở chỉ có thể chuyển sang Đã đóng.`}
        onConfirm={() => transition.mutate('open')} onCancel={() => setConfirm(null)} />}
      {lifecycle && <LifecycleDialog kind={lifecycle} examId={exam.id} examTitle={exam.title} endAtMs={toMs(schedule.data?.s.endAt)} nowMs={nowMs} onCancel={() => setLifecycle(null)}
        onDone={kind => { setLifecycle(null); setMessage({ tone: 'success', text: { open: 'Đã mở kỳ thi.', close: 'Đã đóng kỳ thi. Không nhận lượt làm bài mới.', reopen: 'Đã mở lại kỳ thi.' }[kind] }) }} />}
      {editing && <ScheduleDialog examId={exam.id} examTitle={exam.title} status={exam.status} durationMinutes={exam.durationMinutes} onClose={() => setEditing(false)}
        onSaved={() => setMessage({ tone: 'success', text: 'Đã lưu lịch. Người liên quan sẽ nhận thông báo đổi lịch.' })} />}
      {confirm === 'delete' && <ConfirmDialog title="Xóa đề thi bản nháp?" confirmLabel="Xóa đề thi" tone="danger" busy={transition.isPending}
        description="Đề thi và cấu hình sẽ bị xóa vĩnh viễn. Câu hỏi trong Ngân hàng không bị ảnh hưởng." onConfirm={() => transition.mutate('delete')} onCancel={() => setConfirm(null)} />}
    </div>
  )
}

function Overview({ exam, questions, schedule, viewerTz, onSchedule }: { exam: Exam; questions: (Question | undefined)[]; schedule?: Schedule; viewerTz: string; onSchedule: () => void }) {
  const w = schedule ? windowText(schedule, viewerTz) : null
  return (
    <div className="stack-lg">
      <Section title="Thông tin đề thi">
        <dl className="meta">
          <div><dt>Loại</dt><dd>{EXAM_TYPE_LABEL[exam.type]}</dd></div>
          <div><dt>Thời lượng</dt><dd>{exam.durationMinutes} phút</dd></div>
          <div><dt>Lịch thi</dt><dd style={{ fontSize: '1rem' }}>{w ? <>{w.primary}{w.zone && <><br /><span className="muted">{w.zone}</span></>}</> : '…'}<br /><button type="button" className="btn btn-ghost btn-sm" style={{ paddingLeft: 0 }} onClick={onSchedule}>Xem tab Lịch</button></dd></div>
          <div><dt>Số câu / tổng điểm</dt><dd>{exam.questionIds.length} câu · {formatScore(exam.totalScore)} điểm</dd></div>
          <div><dt>Điểm đạt</dt><dd>{exam.passingScore === null ? 'Không đặt' : formatScore(exam.passingScore)}</dd></div>
          <div><dt>Giám sát trình duyệt</dt><dd>{policySummary(exam.integrityPolicy)}</dd></div>
          <div><dt>Đảo câu hỏi / lựa chọn</dt><dd>{exam.shuffleQuestions ? 'Có' : 'Không'} / {exam.shuffleOptions ? 'Có' : 'Không'}</dd></div>
          <div><dt>Tạo lúc</dt><dd>{formatDateTime(exam.createdAt)}</dd></div>
        </dl>
        {exam.description && <div className="stack-sm" style={{ marginTop: 16 }}><span className="field-label">Hướng dẫn cho thí sinh</span><p className="prewrap small">{exam.description}</p></div>}
      </Section>
      <Section title="Câu hỏi và điểm" bodyClassName=""
        description={exam.status === 'DRAFT' ? 'Đề thi quyết định điểm cho từng câu.' : 'Phiên bản câu hỏi đã được cố định khi xuất bản. Chỉ hiển thị mã câu hỏi và điểm; không lấy câu hiện tại để thay bản chụp.'}>
        <div className="table-wrap">
          <table className="table">
            <thead><tr><th scope="col">#</th><th scope="col">Câu hỏi</th><th scope="col">Loại</th><th scope="col" style={{ textAlign: 'right' }}>Điểm</th></tr></thead>
            <tbody>
              {exam.questions.map((item, i) => {
                const q = questions[i]
                return (
                  <tr key={item.questionId}>
                    <td className="mono muted">{i + 1}</td>
                    <td><div className="truncate-2" style={{ maxWidth: 640 }}>{q ? q.content : <span className="muted">Câu hỏi {shortId(item.questionId)} {exam.status === 'DRAFT' ? '(chưa xác minh được nội dung)' : '(bản chụp đã xuất bản)'}</span>}</div></td>
                    <td className="small">{q ? <>{QUESTION_TYPE_LABEL[q.questionType]}{exam.status === 'DRAFT' && <div style={{ marginTop: 4 }}><QuestionStatusBadge status={q.status} /></div>}</> : '-'}</td>
                    <td className="mono" style={{ textAlign: 'right' }}>{formatScore(item.weight)}</td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      </Section>
    </div>
  )
}

function Participation({ exam, manage }: { exam: Exam; manage: boolean }) {
  const qc = useQueryClient()
  const editable = manage && exam.status === 'DRAFT'
  const [policy, setPolicy] = useState<Policy>({ accessMode: exam.accessMode, maxParticipants: exam.maxParticipants, maxConcurrentSessions: exam.maxConcurrentSessions, attemptLimit: exam.attemptLimit })
  const [message, setMessage] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const run = useMutation({
    mutationFn: (fn: () => Promise<unknown>) => fn(),
    onSuccess: async () => { await Promise.all([qc.invalidateQueries({ queryKey: ['exam', exam.id] }), qc.invalidateQueries({ queryKey: ['participants', exam.id] })]) },
    onError: e => setMessage({ tone: 'danger', text: errorText(e) }),
  })
  function savePolicy(e: FormEvent) {
    e.preventDefault()
    const problem = policyError(policy)
    if (problem) { setMessage({ tone: 'danger', text: problem }); return }
    setMessage(null)
    run.mutate(() => examApi.policy(exam.id, { ...policy, joinCode: policy.accessMode === 'JOIN_CODE' ? policy.joinCode : undefined })
      .then(() => setMessage({ tone: 'success', text: 'Đã lưu chính sách tham gia.' })))
  }
  return (
    <div className="stack-lg">
      {message && <Notice tone={message.tone} live>{message.text}</Notice>}
      <Section title="Chính sách tham gia" description={editable ? 'Chỉ chỉnh được khi đề ở trạng thái Bản nháp.' : 'Đã cố định cùng đề thi.'}>
        {editable ? (
          <form onSubmit={savePolicy} className="stack-lg">
            <PolicyFields value={policy} onChange={setPolicy} />
            {exam.accessMode === 'JOIN_CODE' && <p className="small muted">Mã tham gia hiện tại: <strong className="mono">{exam.code}</strong></p>}
            <div><button type="submit" className="btn btn-primary" disabled={run.isPending}>{run.isPending ? 'Đang lưu…' : 'Lưu chính sách'}</button></div>
          </form>
        ) : (
          <dl className="meta">
            <div><dt>Cách tham gia</dt><dd>{ACCESS_MODE_LABEL[exam.accessMode]}</dd></div>
            {exam.accessMode === 'JOIN_CODE' && <div><dt>Mã tham gia</dt><dd className="mono">{exam.code}</dd></div>}
            <div><dt>Người tham gia tối đa</dt><dd>{exam.maxParticipants}</dd></div>
            <div><dt>Phiên làm bài đồng thời</dt><dd>{exam.maxConcurrentSessions}</dd></div>
            <div><dt>Lượt làm bài mỗi thí sinh</dt><dd>{exam.attemptLimit}</dd></div>
          </dl>
        )}
      </Section>
      <Section title="Thí sinh" description="Danh sách, số liệu và lượt làm bài lấy từ máy chủ. Người xem Giám thị chỉ xem." bodyClassName="card-body">
        <ParticipantsSection examId={exam.id} examStatus={exam.status} maxParticipants={exam.maxParticipants} manage={manage} />
      </Section>
    </div>
  )
}

function Collaborators({ exam, owner, members }: { exam: Exam; owner: boolean; members: Record<string, MemberRole> }) {
  const qc = useQueryClient()
  const [userId, setUserId] = useState('')
  const [role, setRole] = useState<MemberRole>('MANAGER')
  const [message, setMessage] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const run = useMutation({
    mutationFn: (fn: () => Promise<unknown>) => fn(),
    onSuccess: () => qc.invalidateQueries({ queryKey: ['members', exam.id] }),
    onError: e => setMessage({ tone: 'danger', text: errorText(e) }),
  })
  function invite(e: FormEvent) {
    e.preventDefault()
    if (!UUID.test(userId.trim())) { setMessage({ tone: 'danger', text: 'Nhập mã người dùng (UUID) hợp lệ của giáo viên.' }); return }
    setMessage(null)
    run.mutate(() => examApi.invite(exam.id, userId.trim(), role).then(() => { setUserId(''); setMessage({ tone: 'success', text: 'Đã gửi lời mời. Người được mời cần chấp nhận trong trang Đề thi.' }) }))
  }
  return (
    <div className="stack-lg">
      {message && <Notice tone={message.tone} live>{message.text}</Notice>}
      <Section title="Vai trò trong kỳ thi" bodyClassName="">
        <div className="table-wrap"><table className="table">
          <thead><tr><th scope="col">Vai trò</th><th scope="col">Được phép</th><th scope="col">Không được phép</th></tr></thead>
          <tbody>
            <tr><td><strong>Chủ sở hữu</strong></td><td className="small">Mọi thao tác, gồm mời và xóa cộng tác viên</td><td className="small">-</td></tr>
            <tr><td><strong>Quản lý</strong></td><td className="small">Sửa bản nháp, chính sách, phân công, xuất bản, mở, đóng kỳ thi, chấm bài</td><td className="small">Quản lý cộng tác viên</td></tr>
            <tr><td><strong>Giám thị</strong></td><td className="small">Xem đề thi, thí sinh và lượt làm bài</td><td className="small">Xuất bản, mở, đóng kỳ thi; chấm bài; sửa đề</td></tr>
          </tbody>
        </table></div>
      </Section>
      <Section title="Cộng tác viên" bodyClassName="">
        {owner && (
          <form onSubmit={invite} className="card-body stack-sm" style={{ borderBottom: '1px solid var(--color-border)' }}>
            <div className="grid-2" style={{ gridTemplateColumns: 'minmax(0, 2fr) minmax(160px, 1fr)' }}>
              <Field label="Mã người dùng của giáo viên" htmlFor="invite-user"><input id="invite-user" className="input mono" value={userId} onChange={e => setUserId(e.target.value)} /></Field>
              <Field label="Vai trò" htmlFor="invite-role">
                <select id="invite-role" className="input" value={role} onChange={e => setRole(e.target.value as MemberRole)}>
                  <option value="MANAGER">Quản lý</option><option value="PROCTOR">Giám thị</option>
                </select>
              </Field>
            </div>
            <div><button type="submit" className="btn btn-secondary" disabled={run.isPending || !userId.trim()}><UserPlus size={16} aria-hidden /> Gửi lời mời</button></div>
          </form>
        )}
        <div className="table-wrap"><table className="table">
          <thead><tr><th scope="col">Người dùng</th><th scope="col">Vai trò</th>{owner && <th scope="col"><span className="sr-only">Thao tác</span></th>}</tr></thead>
          <tbody>
            <tr><td className="mono small">{exam.ownerUserId}</td><td><Badge tone="info">Chủ sở hữu</Badge></td>{owner && <td />}</tr>
            {Object.entries(members).map(([uid, r]) => (
              <tr key={uid}>
                <td className="mono small">{uid}</td><td><Badge>{MEMBER_ROLE_LABEL[r]}</Badge></td>
                {owner && <td style={{ textAlign: 'right' }}><button type="button" className="btn btn-danger-ghost btn-sm" disabled={run.isPending}
                  onClick={() => run.mutate(() => examApi.removeMember(exam.id, uid))}>Xóa</button></td>}
              </tr>
            ))}
          </tbody>
        </table></div>
        <p className="card-body xsmall muted">Lời mời đang chờ chấp nhận chưa hiển thị trong danh sách này.</p>
      </Section>
    </div>
  )
}

function Attempts({ exam }: { exam: Exam }) {
  const sessions = useQuery({ queryKey: ['exam-sessions', exam.id], queryFn: () => examApi.sessions(exam.id), refetchInterval: exam.status === 'OPEN' ? 15_000 : false })
  const active = sessions.data?.filter(s => s.status === 'IN_PROGRESS').length ?? 0
  return (
    <Section title="Lượt làm bài" bodyClassName=""
      description={sessions.data ? `${sessions.data.length} lượt · ${active}/${exam.maxConcurrentSessions} phiên đang làm` : undefined}
      actions={<button type="button" className="btn btn-ghost btn-sm" onClick={() => void sessions.refetch()} disabled={sessions.isFetching}><RefreshCw size={14} aria-hidden /> Làm mới</button>}>
      {sessions.isLoading ? <LoadingState rows={3} /> : sessions.isError ? <ErrorState error={sessions.error} onRetry={() => void sessions.refetch()} />
        : sessions.data!.length === 0 ? <EmptyState title="Chưa có lượt làm bài" description="Lượt làm bài xuất hiện khi thí sinh bắt đầu trong kỳ thi đang mở. Muốn vô hiệu hóa một lượt, mở thí sinh ở tab Tham gia." />
        : <div className="table-wrap"><table className="table">
            <thead><tr><th scope="col">Thí sinh</th><th scope="col">Lượt</th><th scope="col">Trạng thái</th><th scope="col">Hiệu lực</th><th scope="col">Bắt đầu</th><th scope="col">Hết giờ</th></tr></thead>
            <tbody>{sessions.data!.map(s => (
              <tr key={s.sessionId}>
                <td className="mono small">{shortId(s.userId)}</td><td className="mono">{s.attemptNumber}</td><td><SessionStatusBadge status={s.status} /></td>
                <td className="small">{s.invalidated ? <><Badge tone="danger">Đã vô hiệu</Badge>{s.invalidationReason && <span className="xsmall muted" style={{ display: 'block' }}>{s.invalidationReason}</span>}</> : 'Hợp lệ'}</td>
                <td className="small">{formatDateTime(s.startedAt)}</td><td className="small">{formatDateTime(s.expiresAt)}</td>
              </tr>
            ))}</tbody>
          </table></div>}
    </Section>
  )
}

const AUDIT_LABEL: Record<string, string> = {
  EXAM_CREATED: 'Tạo đề thi', EXAM_UPDATED: 'Cập nhật bản nháp', EXAM_PUBLISHED: 'Xuất bản', EXAM_OPENED: 'Mở kỳ thi', EXAM_CLOSED: 'Đóng kỳ thi', EXAM_REOPENED: 'Mở lại kỳ thi', EXAM_SCHEDULE_CHANGED: 'Thay đổi lịch thi',
  PARTICIPATION_POLICY_UPDATED: 'Cập nhật chính sách tham gia', PARTICIPANT_ASSIGNED: 'Phân công thí sinh', PARTICIPANT_REMOVED: 'Bỏ phân công thí sinh',
  MANAGER_INVITED: 'Mời Quản lý', PROCTOR_INVITED: 'Mời Giám thị', MANAGER_ADDED: 'Quản lý chấp nhận lời mời', PROCTOR_ADDED: 'Giám thị chấp nhận lời mời',
  MANAGER_REMOVED: 'Xóa Quản lý', PROCTOR_REMOVED: 'Xóa Giám thị',
  INTEGRITY_POLICY_UPDATED: 'Cập nhật giám sát trình duyệt', EXAM_INTEGRITY_LOCKED: 'Khóa toàn kỳ thi', EXAM_INTEGRITY_UNLOCKED: 'Mở khóa kỳ thi',
}

function Audit({ exam }: { exam: Exam }) {
  const audit = useQuery({ queryKey: ['exam-audit', exam.id], queryFn: () => examApi.audit(exam.id) })
  return (
    <Section title="Nhật ký thao tác" bodyClassName="">
      {audit.isLoading ? <LoadingState rows={3} /> : audit.isError ? <ErrorState error={audit.error} onRetry={() => void audit.refetch()} />
        : audit.data!.length === 0 ? <EmptyState title="Chưa có nhật ký" />
        : <div className="table-wrap"><table className="table">
            <thead><tr><th scope="col">Thời gian</th><th scope="col">Thao tác</th><th scope="col">Người thực hiện</th></tr></thead>
            <tbody>{audit.data!.slice().reverse().map(a => (
              <tr key={a.id}><td className="small">{formatDateTime(a.timestamp)}</td><td className="small">{AUDIT_LABEL[a.action] ?? a.action}</td><td className="mono small">{shortId(a.actorUserId)}</td></tr>
            ))}</tbody>
          </table></div>}
    </Section>
  )
}

function ScheduleTab({ exam, manage, schedule, loading, error, onRetry, viewerTz, onEdit }: {
  exam: Exam; manage: boolean; schedule?: Schedule; loading: boolean; error: unknown; onRetry: () => void; viewerTz: string; onEdit: () => void
}) {
  if (loading) return <LoadingState rows={3} />
  if (error || !schedule) return <ErrorState error={error} onRetry={onRetry} />
  const w = windowText(schedule, viewerTz)
  const reminders = normalizeReminders(schedule.reminderOffsetsMinutes)
  const none = !schedule.startAt && !schedule.endAt
  return (
    <div className="stack-lg">
      <Section title="Lịch thi" description="Giờ theo múi giờ của kỳ thi; máy chủ quyết định ai được tham gia và bắt đầu."
        actions={manage ? <button type="button" className="btn btn-primary btn-sm" onClick={onEdit}><CalendarClock size={14} aria-hidden /> {exam.status === 'CLOSED' ? 'Xem lịch' : 'Sửa lịch'}</button> : undefined}>
        {!manage && <Notice tone="info">Giám thị chỉ xem lịch; không sửa lịch hay mở/đóng kỳ thi.</Notice>}
        {none && <Notice tone="info" title="Chưa đặt lịch">Kỳ thi không có lịch vẫn xuất bản, mở và đóng thủ công được. Đặt lịch để hiện trên Lịch và nhắc thí sinh.</Notice>}
        <dl className="meta" style={{ marginTop: none ? 16 : 0 }}>
          <div><dt>Cửa sổ làm bài</dt><dd style={{ fontSize: '1rem' }}>{w.primary}{w.zone && <><br /><span className="muted">{w.zone}</span></>}</dd></div>
          {w.mineText && <div><dt>Giờ của bạn</dt><dd style={{ fontSize: '1rem' }}>{w.mineText}<br /><span className="muted">{w.mineZone} · {viewerTz}</span></dd></div>}
          <div><dt>Cách tính hạn nộp</dt><dd style={{ fontSize: '1rem' }}>{ruleLabel(schedule.deadlineRule)}<br /><span className="xsmall muted">{RULE_HELP[schedule.deadlineRule]}</span></dd></div>
          <div><dt>Tự động mở</dt><dd>{schedule.autoOpen ? 'Bật' : 'Tắt'}</dd></div>
          <div><dt>Tự động đóng</dt><dd>{schedule.autoClose ? 'Bật' : 'Tắt'}</dd></div>
          <div><dt>Nhắc trước giờ bắt đầu</dt><dd style={{ fontSize: '1rem' }}>{reminders.length ? reminders.map(m => offsetLabel(m)).join(', ') : 'Tắt'}</dd></div>
          <div><dt>Phiên bản lịch</dt><dd>{schedule.scheduleRevision}</dd></div>
        </dl>
      </Section>
    </div>
  )
}

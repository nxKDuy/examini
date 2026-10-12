import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FileCheck2, PlayCircle, RotateCcw } from 'lucide-react'
import { examApi } from './examApi'
import { rememberAttempt } from './attemptHints'
import { calendarApi } from '../calendar/calendarApi'
import { countdownLine, reasonText, RULE_HELP, ruleLabel, windowText } from '../calendar/calendarModel'
import { StateBadge } from '../calendar/ExamCard'
import FollowButton from '../calendar/FollowButton'
import { useBoundaryRefetch, useNow, useViewerZone } from '../calendar/useCalendar'
import { useAuthStore } from '../../store/authStore'
import StudentAttempts, { RevokedNotice } from '../participants/StudentAttempts'
import { monitored, policySummary } from '../integrity/integrityModel'
import { integrityApi } from '../integrity/integrityApi'
import { exitFullscreen, fullscreenSupported, requestFullscreen } from '../../lib/fullscreen'
import { useParticipation } from '../participants/useParticipation'
import { ErrorState, LoadingState, Notice, PageHeader, Section } from '../../components/ui'
import { errorStatus, errorText } from '../../lib/errors'
import { EXAM_TYPE_LABEL, formatScore } from '../../lib/format'

export default function ExamLobbyPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const user = useAuthStore(s => s.user)
  const viewerTz = useViewerZone()
  const [ready, setReady] = useState(false)
  const [starting, setStarting] = useState(false)
  const [error, setError] = useState('')
  const lobby = useQuery({ queryKey: ['lobby', id], queryFn: () => examApi.lobby(id!), retry: false })
  // The frozen policy decides whether Start must enter fullscreen first (contract A).
  const integrityPolicy = useQuery({ queryKey: ['integrity-exam-policy', id], queryFn: () => integrityApi.examPolicy(id!), retry: false, enabled: lobby.isSuccess })
  const [fsProblem, setFsProblem] = useState<'denied' | null>(null)
  // The backend decides who may join / start / resume and why not; this page only shows it.
  const avail = useQuery({
    queryKey: ['availability', id], queryFn: async () => { const a = await calendarApi.availability(id!); return { a, clockOffset: Date.parse(a.serverNow) - Date.now() } },
    retry: false, refetchOnWindowFocus: true, refetchInterval: 30_000,
  })
  const a = avail.data?.a
  const part = useParticipation(id)
  const mine = part.data ?? null
  const revoked = mine?.status === 'REVOKED'
  const nowMs = useNow(avail.data?.clockOffset ?? 0)
  // Re-ask the backend just after the window opens/closes or the attempt expires; the clock only triggers the question.
  useBoundaryRefetch(a ? [a] : [], avail.data?.clockOffset ?? 0, avail.refetch)

  const join = useMutation({
    mutationFn: () => examApi.join(id!),
    onSuccess: async () => { await Promise.all(['lobby', 'availability', 'my-exams', 'calendar'].map(k => qc.invalidateQueries({ queryKey: [k] }))); setError('') },
    onError: e => setError(errorText(e)),
  })

  if (avail.isLoading || (lobby.isLoading && !a)) return <LoadingState rows={5} />
  if (avail.isError || !a) {
    return <div className="stack">
      <ErrorState error={avail.error} onRetry={() => void avail.refetch()} />
      <div style={{ textAlign: 'center' }}><Link to="/my-exams" className="btn btn-secondary">Về Kỳ thi của tôi</Link></div>
    </div>
  }
  const exam = lobby.data
  const preview = !exam && (lobby.isError ? errorStatus(lobby.error) === 403 || errorStatus(lobby.error) === 404 : false)
  if (lobby.isError && !preview) return <ErrorState error={lobby.error} onRetry={() => void lobby.refetch()} />

  const w = windowText(a, viewerTz)
  const countdown = countdownLine(a, nowMs)
  const resumeId = a.canResume && a.latestSessionStatus === 'IN_PROGRESS' ? a.latestSessionId : null
  const finishedId = a.latestSessionId && a.latestSessionStatus && a.latestSessionStatus !== 'IN_PROGRESS' ? a.latestSessionId : null
  const why = !a.canStart && !a.canResume ? reasonText(a.reasonCode) : null

  const needsFullscreen = !!(integrityPolicy.data ?? lobby.data?.integrityPolicy)?.requireFullscreen
  const fsUnsupported = needsFullscreen && !fullscreenSupported()

  async function start() {
    setError(''); setFsProblem(null)
    if (needsFullscreen) {
      if (fsUnsupported) return
      // Requested synchronously in the click (user gesture); the attempt starts only once fullscreen is active.
      if (!(await requestFullscreen())) { setFsProblem('denied'); return }
    }
    setStarting(true)
    try {
      await examApi.preflight(id!)
      // Start resumes an existing IN_PROGRESS attempt server-side instead of creating a duplicate.
      const session = await examApi.start(id!)
      if (user) rememberAttempt(user.id, id!, session.sessionId)
      navigate(`/exam/take/${session.sessionId}`)
    } catch (e) {
      if (needsFullscreen) exitFullscreen()
      setError(errorText(e))
      void avail.refetch()
    } finally {
      setStarting(false)
    }
  }

  return (
    <div className="stack-lg" style={{ maxWidth: 880 }}>
      <PageHeader title={a.examTitle} crumbs={[{ label: 'Kỳ thi của tôi', to: '/my-exams' }, { label: preview ? 'Xem trước' : 'Phòng chờ' }]}
        description={<span className="row"><StateBadge state={a.calendarState} />{exam && <span>{EXAM_TYPE_LABEL[exam.type]}</span>}</span>} />

      {preview && (
        <Notice tone="info" title="Xem trước">
          Bạn chưa tham gia kỳ thi này nên chỉ thấy lịch và điều kiện. Nội dung đề chỉ hiện sau khi tham gia và đến giờ bắt đầu.
          <div className="row" style={{ marginTop: 10 }}>
            <button type="button" className="btn btn-primary btn-sm" disabled={!a.canJoin || join.isPending} onClick={() => join.mutate()}>{join.isPending ? 'Đang tham gia…' : 'Tham gia kỳ thi'}</button>
            <FollowButton examId={a.examId} followed={a.followed} onError={setError} />
          </div>
          {!a.canJoin && <p className="small" style={{ marginTop: 8 }}>{reasonText(a.reasonCode) ?? 'Chưa thể tham gia lúc này.'}</p>}
        </Notice>
      )}

      {resumeId && (
        <Notice tone="info" title="Bạn có lượt làm bài đang dở" action={<Link to={`/exam/take/${resumeId}`} className="btn btn-primary" onClick={() => { if (needsFullscreen && !fsUnsupported) void requestFullscreen() }}><PlayCircle size={16} aria-hidden /> Tiếp tục làm bài</Link>}>
          {countdown ?? 'Thời gian vẫn tiếp tục chạy trên máy chủ.'}
        </Notice>
      )}

      {mine && revoked && <RevokedNotice mine={mine} />}
      {mine && <StudentAttempts examId={id!} mine={mine} />}

      <Section title="Lịch và thời gian làm bài">
        <dl className="meta">
          <div><dt>Cửa sổ làm bài</dt><dd style={{ fontSize: '1rem' }}>{w.primary}{w.zone && <><br /><span className="muted">{w.zone}</span></>}</dd></div>
          {w.mineText && <div><dt>Giờ của bạn</dt><dd style={{ fontSize: '1rem' }}>{w.mineText}<br /><span className="muted">{w.mineZone} · {viewerTz}</span></dd></div>}
          {countdown && !resumeId && <div><dt>Đếm ngược</dt><dd>{countdown}</dd></div>}
          <div><dt>Thời lượng làm bài</dt><dd>{a.durationMinutes} phút (tính từ lúc bạn bắt đầu)</dd></div>
          <div><dt>Cách tính hạn nộp</dt><dd style={{ fontSize: '1rem' }}>{ruleLabel(a.deadlineRule)}<br /><span className="xsmall muted">{RULE_HELP[a.deadlineRule]}</span></dd></div>
          {a.attemptsRemaining !== null && <div><dt>Lượt còn lại</dt><dd>{a.attemptsRemaining}</dd></div>}
        </dl>
      </Section>

      {exam && (
        <Section title="Thông tin bài thi">
          <dl className="meta">
            <div><dt>Số câu hỏi</dt><dd>{exam.questionIds.length}</dd></div>
            <div><dt>Tổng điểm</dt><dd>{formatScore(exam.totalScore)}</dd></div>
            <div><dt>Điểm đạt</dt><dd>{exam.passingScore === null ? 'Không đặt' : formatScore(exam.passingScore)}</dd></div>
            <div><dt>Số lượt được làm</dt><dd>{exam.attemptLimit}</dd></div>
          </dl>
        </Section>
      )}
      {exam?.description && <Section title="Hướng dẫn của giáo viên"><p className="prewrap">{exam.description}</p></Section>}

      <Section title="Quy định làm bài">
        <ul className="stack-sm small" style={{ margin: 0, paddingLeft: 18 }}>
          <li>Thời gian bắt đầu tính khi bạn bấm Bắt đầu và do máy chủ kiểm soát; tải lại trang không làm dừng đồng hồ.</li>
          <li>Câu trả lời được lưu tự động. Tải lại trang hoặc mở lại sẽ khôi phục lượt đang làm và các câu đã lưu.</li>
          <li>Hết giờ, hệ thống tự động nộp những câu trả lời đã lưu.</li>
          <li>Sau khi nộp, không thể thay đổi câu trả lời.</li>
          {monitored(exam?.integrityPolicy) && <li>Kỳ thi có ghi nhận tín hiệu trình duyệt: {policySummary(exam!.integrityPolicy)}. Tín hiệu là dữ liệu ghi nhận, không phải kết luận gian lận.</li>}
        </ul>
      </Section>

      {finishedId && (
        <Notice tone="success" title="Lượt gần nhất đã kết thúc" action={<Link to={`/exam/result/${finishedId}`} className="btn btn-secondary btn-sm"><FileCheck2 size={16} aria-hidden /> Xem kết quả</Link>}>
          Lượt gần nhất của bạn đã được nộp.
        </Notice>
      )}

      {!preview && !resumeId && !revoked && (
        <section className="card card-body stack">
          {why ? <Notice tone="info" title="Chưa bắt đầu được">{why}</Notice> : <p className="small">Kỳ thi đang mở cho bạn. Bấm Bắt đầu khi sẵn sàng.</p>}
          {a.canStart && <label className="check"><input type="checkbox" checked={ready} onChange={e => setReady(e.target.checked)} /><span>Tôi đã đọc hướng dẫn và sẵn sàng bắt đầu.</span></label>}
          {needsFullscreen && !fsUnsupported && a.canStart && <p className="small">Kỳ thi yêu cầu toàn màn hình: bấm Bắt đầu sẽ bật toàn màn hình rồi mới tạo lượt làm bài.</p>}
          {fsUnsupported && <Notice tone="warning" title="Trình duyệt không hỗ trợ toàn màn hình">Kỳ thi này yêu cầu làm bài ở chế độ toàn màn hình nhưng trình duyệt hiện tại không có chức năng này. Hãy dùng trình duyệt máy tính bản mới (Chrome, Edge, Firefox hoặc Safari). Lượt làm bài chưa được tạo.</Notice>}
          {fsProblem && <Notice tone="danger" live title="Chưa bật được toàn màn hình"
            action={<button type="button" className="btn btn-secondary btn-sm" onClick={() => void start()}>Thử lại</button>}>
            Trình duyệt đã từ chối hoặc chặn chế độ toàn màn hình. Lượt làm bài chưa được tạo; bấm Thử lại và cho phép toàn màn hình.</Notice>}
          {error && <Notice tone="danger" live>{error}</Notice>}
          <div className="row">
            <button type="button" className="btn btn-primary btn-lg" disabled={!a.canStart || !ready || starting || fsUnsupported} aria-busy={starting} onClick={() => void start()}>
              <PlayCircle size={18} aria-hidden /> {starting ? 'Đang kiểm tra điều kiện…' : finishedId ? 'Bắt đầu lượt mới' : 'Bắt đầu làm bài'}
            </button>
            {!a.canStart && <button type="button" className="btn btn-ghost" onClick={() => void avail.refetch()}><RotateCcw size={16} aria-hidden /> Kiểm tra lại</button>}
          </div>
        </section>
      )}
      {preview && error && <Notice tone="danger" live>{error}</Notice>}
    </div>
  )
}

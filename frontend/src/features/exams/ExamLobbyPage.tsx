import { useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { FileCheck2, PlayCircle, RotateCcw } from 'lucide-react'
import { AVAILABILITY_HELP, availability, examApi } from './examApi'
import { lastAttempt, rememberAttempt } from './attemptHints'
import { AvailabilityBadge } from './StudentExamParts'
import { useAuthStore } from '../../store/authStore'
import { ErrorState, LoadingState, Notice, PageHeader, Section } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { EXAM_TYPE_LABEL, formatDateTime, formatDuration, formatScore } from '../../lib/format'

export default function ExamLobbyPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const user = useAuthStore(s => s.user)
  const [ready, setReady] = useState(false)
  const [starting, setStarting] = useState(false)
  const [error, setError] = useState('')
  const lobby = useQuery({ queryKey: ['lobby', id], queryFn: () => examApi.lobby(id!), retry: false })
  const hint = lastAttempt(user?.id, id!)
  const previous = useQuery({ queryKey: ['session', hint], queryFn: () => examApi.session(hint!), enabled: !!hint, retry: false })

  if (lobby.isLoading) return <LoadingState rows={5} />
  if (lobby.isError || !lobby.data) {
    return <div className="stack">
      <ErrorState error={lobby.error} onRetry={() => void lobby.refetch()} />
      <div style={{ textAlign: 'center' }}><Link to="/my-exams" className="btn btn-secondary">Về Kỳ thi của tôi</Link></div>
    </div>
  }
  const exam = lobby.data
  const state = availability(exam)
  const active = previous.data?.status === 'IN_PROGRESS' && Date.parse(previous.data.expiresAt) > Date.parse(previous.data.serverNow) ? previous.data : null
  const finished = previous.data && !active ? previous.data : null

  async function start() {
    setStarting(true); setError('')
    try {
      await examApi.preflight(exam.id)
      // Start resumes an existing IN_PROGRESS attempt server-side instead of creating a duplicate.
      const session = await examApi.start(exam.id)
      if (user) rememberAttempt(user.id, exam.id, session.sessionId)
      navigate(`/exam/take/${session.sessionId}`)
    } catch (e) {
      setError(errorText(e))
      void lobby.refetch()
    } finally {
      setStarting(false)
    }
  }

  return (
    <div className="stack-lg" style={{ maxWidth: 880 }}>
      <PageHeader title={exam.title} crumbs={[{ label: 'Kỳ thi của tôi', to: '/my-exams' }, { label: 'Phòng chờ' }]}
        description={<span className="row"><AvailabilityBadge exam={exam} /><span>{EXAM_TYPE_LABEL[exam.type]}</span></span>} />

      {active && (
        <Notice tone="info" title="Bạn có lượt làm bài đang dở"
          action={<Link to={`/exam/take/${active.sessionId}`} className="btn btn-primary"><PlayCircle size={16} aria-hidden /> Tiếp tục làm bài</Link>}>
          Lượt {active.attemptNumber} còn khoảng {formatDuration((Date.parse(active.expiresAt) - Date.parse(active.serverNow)) / 1000)}. Thời gian vẫn tiếp tục chạy trên máy chủ.
        </Notice>
      )}

      <Section title="Thông tin bài thi">
        <dl className="meta">
          <div><dt>Thời lượng</dt><dd>{exam.durationMinutes} phút</dd></div>
          <div><dt>Số câu hỏi</dt><dd>{exam.questionIds.length}</dd></div>
          <div><dt>Tổng điểm</dt><dd>{formatScore(exam.totalScore)}</dd></div>
          <div><dt>Điểm đạt</dt><dd>{exam.passingScore === null ? 'Không đặt' : formatScore(exam.passingScore)}</dd></div>
          <div><dt>Mở từ</dt><dd>{formatDateTime(exam.startTime, 'Không giới hạn')}</dd></div>
          <div><dt>Nhận lượt mới đến</dt><dd>{formatDateTime(exam.endTime, 'Không giới hạn')}</dd></div>
          <div><dt>Số lượt được làm</dt><dd>{exam.attemptLimit}</dd></div>
        </dl>
      </Section>

      {exam.description && <Section title="Hướng dẫn của giáo viên"><p className="prewrap">{exam.description}</p></Section>}

      <Section title="Quy định làm bài">
        <ul className="stack-sm small" style={{ margin: 0, paddingLeft: 18 }}>
          <li>Thời gian bắt đầu tính khi bạn bấm Bắt đầu và do máy chủ kiểm soát; tải lại trang không làm dừng đồng hồ.</li>
          <li>Câu trả lời được lưu tự động. Tải lại trang hoặc mở lại sẽ khôi phục lượt đang làm và các câu đã lưu.</li>
          <li>Hết giờ, hệ thống tự động nộp những câu trả lời đã lưu.</li>
          <li>Sau khi nộp, không thể thay đổi câu trả lời.</li>
          {exam.attemptLimit > 1 && <li>Bạn có tối đa {exam.attemptLimit} lượt làm bài.</li>}
        </ul>
      </Section>

      {finished && (
        <Notice tone="success" title="Lượt gần nhất đã kết thúc"
          action={<Link to={`/exam/result/${finished.sessionId}`} className="btn btn-secondary btn-sm"><FileCheck2 size={16} aria-hidden /> Xem kết quả</Link>}>
          Lượt {finished.attemptNumber} đã được nộp.
        </Notice>
      )}

      {!active && (
        <section className="card card-body stack">
          <p className="small">{AVAILABILITY_HELP[state]}</p>
          {state === 'OPEN' && <label className="check"><input type="checkbox" checked={ready} onChange={e => setReady(e.target.checked)} />
            <span>Tôi đã đọc hướng dẫn và sẵn sàng bắt đầu.</span></label>}
          {error && <Notice tone="danger" live>{error}</Notice>}
          <div className="row">
            <button type="button" className="btn btn-primary btn-lg" disabled={state !== 'OPEN' || !ready || starting} aria-busy={starting} onClick={() => void start()}>
              <PlayCircle size={18} aria-hidden /> {starting ? 'Đang kiểm tra điều kiện…' : finished ? 'Bắt đầu lượt mới' : 'Bắt đầu làm bài'}
            </button>
            {state !== 'OPEN' && <button type="button" className="btn btn-ghost" onClick={() => void lobby.refetch()}><RotateCcw size={16} aria-hidden /> Kiểm tra lại</button>}
          </div>
        </section>
      )}
    </div>
  )
}

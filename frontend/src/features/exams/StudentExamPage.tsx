import { useCallback, useEffect, useRef, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { AlertCircle, CheckCircle2, ChevronLeft, ChevronRight, CloudOff, LayoutGrid, Maximize, Send, ShieldAlert, Timer } from 'lucide-react'
import BlockingOverlay from '../integrity/BlockingOverlay'
import { exitFullscreen } from '../../lib/fullscreen'
import { useT, t as tt } from '../../lib/i18n'
import { examApi, type SafeQuestion, type Session } from './examApi'
import { rememberAttempt } from './attemptHints'
import { useAuthStore } from '../../store/authStore'
import { useIntegrityMonitor } from '../integrity/useIntegrityMonitor'
import { signalState, type StudentSignalState } from '../integrity/integrityModel'
import { ConfirmDialog } from '../../components/Dialog'
import { ErrorState, Notice, Spinner } from '../../components/ui'
import { errorInfo, errorText } from '../../lib/errors'
import { QUESTION_TYPE_LABEL, formatClock, formatDuration, formatScore } from '../../lib/format'

type SaveState = 'pending' | 'saving' | 'saved' | 'error'
const TEXT_DEBOUNCE_MS = 800
const RESYNC_MS = 60_000

export default function StudentExamPage() {
  const { id } = useParams()
  const navigate = useNavigate()
  const userId = useAuthStore(s => s.user?.id)
  const [session, setSession] = useState<Session | null>(null)
  const [loadError, setLoadError] = useState<unknown>(null)
  const [answers, setAnswers] = useState<Record<string, string>>({})
  const [saveState, setSaveState] = useState<Record<string, SaveState>>({})
  const [lastSavedAt, setLastSavedAt] = useState<Date | null>(null)
  const [actionError, setActionError] = useState('')
  const [index, setIndex] = useState(0)
  const [now, setNow] = useState(() => Date.now())
  const [online, setOnline] = useState(() => navigator.onLine)
  const [confirming, setConfirming] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [showPalette, setShowPalette] = useState(false)
  const [announcement, setAnnouncement] = useState('')
  const [signals, setSignals] = useState<StudentSignalState | null>(null)
  const [fsRefused, setFsRefused] = useState(false)
  const t = useT()
  const wasFullscreen = useRef(false)
  const [autoSubmitted, setAutoSubmitted] = useState(false)
  const autoSubmittedRef = useRef(false)

  const offset = useRef(0) // serverNow - clientNow at last sync
  const latest = useRef<Record<string, string>>({})
  const queues = useRef<Record<string, Promise<unknown>>>({})
  const timers = useRef<Record<string, number>>({})
  const stateRef = useRef<Record<string, SaveState>>({})
  const announced = useRef<Set<number>>(new Set())
  const headingRef = useRef<HTMLHeadingElement>(null)
  const submittingRef = useRef(false)

  const setQState = useCallback((qid: string, s: SaveState) => {
    stateRef.current = { ...stateRef.current, [qid]: s }
    setSaveState(stateRef.current)
  }, [])

  const goResult = useCallback(() => navigate(`/exam/result/${id}`, { replace: true }), [navigate, id])

  const sync = useCallback(async (initial = false) => {
    try {
      const s = await examApi.session(id!)
      offset.current = Date.parse(s.serverNow) - Date.now()
      // After an integrity auto-submit the page stays on its own submitted state instead of jumping away.
      if (autoSubmittedRef.current) return
      if (s.status !== 'IN_PROGRESS') { goResult(); return }
      setSession(s)
      if (initial) {
        latest.current = { ...s.answers }
        setAnswers({ ...s.answers })
        if (userId) rememberAttempt(userId, s.examId, s.sessionId)
      }
    } catch (e) {
      if (initial) setLoadError(e)
    }
  }, [id, goResult, userId])

  const loadedFor = useRef<string | null>(null)
  useEffect(() => {
    // Full reset only once per session; token refresh must not overwrite unsaved local answers.
    if (loadedFor.current === id) return
    loadedFor.current = id ?? null
    void sync(true)
  }, [id, sync])
  useEffect(() => {
    const tick = setInterval(() => setNow(Date.now()), 1000)
    const resync = setInterval(() => void sync(), RESYNC_MS)
    const onVisible = () => { if (document.visibilityState === 'visible') void sync() }
    const onOnline = () => { setOnline(true); void sync() }
    const onOffline = () => setOnline(false)
    document.addEventListener('visibilitychange', onVisible)
    window.addEventListener('online', onOnline)
    window.addEventListener('offline', onOffline)
    return () => {
      clearInterval(tick); clearInterval(resync)
      document.removeEventListener('visibilitychange', onVisible)
      window.removeEventListener('online', onOnline); window.removeEventListener('offline', onOffline)
    }
  }, [sync])

  const integrity = useIntegrityMonitor(id, !!session && !autoSubmitted, r => {
    const next = signalState(r)
    setSignals(next)
    if (next.stage === 'submitted') { autoSubmittedRef.current = true; setAutoSubmitted(true); setConfirming(false); return }
    if (r.warningRecommended) setAnnouncement(tt('Đã ghi nhận {0}/{1} tín hiệu. Thêm một tín hiệu nữa, bài sẽ được tự động nộp.', { 0: next.count, 1: next.limit }))
  })
  if (integrity.fullscreen) wasFullscreen.current = true
  // Leaving the attempt (result, auto-submit, navigation) gives the screen back.
  useEffect(() => { if (autoSubmitted) exitFullscreen() }, [autoSubmitted])
  // Real unmount only: StrictMode's dev remount must not drop the fullscreen granted by Start.
  const mountedRef = useRef(false)
  useEffect(() => {
    mountedRef.current = true
    return () => { mountedRef.current = false; window.setTimeout(() => { if (!mountedRef.current) exitFullscreen() }, 0) }
  }, [])
  async function backToFullscreen() {
    setFsRefused(false)
    if (!(await integrity.enterFullscreen())) setFsRefused(true)
  }

  const remaining = session ? Math.max(0, Math.ceil((Date.parse(session.expiresAt) - (now + offset.current)) / 1000)) : 0
  const expired = !!session && remaining <= 0

  // Server decides when time is up; poll until it reports the session finished.
  useEffect(() => {
    if (!expired) return
    void sync()
    const poll = setInterval(() => void sync(), 3000)
    return () => clearInterval(poll)
  }, [expired, sync])

  useEffect(() => {
    for (const mark of [300, 60]) {
      if (remaining > 0 && remaining <= mark && !announced.current.has(mark)) {
        announced.current.add(mark)
        setAnnouncement(mark === 300 ? tt('Còn 5 phút làm bài.') : tt('Còn 1 phút làm bài.'))
      }
    }
  }, [remaining])

  const persist = useCallback((qid: string) => {
    window.clearTimeout(timers.current[qid]); delete timers.current[qid]
    const value = latest.current[qid] ?? ''
    setQState(qid, 'saving')
    const run = (queues.current[qid] ?? Promise.resolve()).catch(() => {}).then(() => examApi.answer(id!, qid, value))
    queues.current[qid] = run
    run.then(() => {
      if (queues.current[qid] !== run) return
      setQState(qid, (latest.current[qid] ?? '') === value ? (timers.current[qid] ? 'pending' : 'saved') : 'pending')
      setLastSavedAt(new Date())
    }, e => {
      if (queues.current[qid] !== run) return
      setQState(qid, 'error')
      const info = errorInfo(e)
      setActionError(info.message)
      if (info.status === 409 || info.status === 403) void sync()
    })
    return run
  }, [id, setQState, sync])

  function change(qid: string, value: string, immediate: boolean) {
    latest.current[qid] = value
    setAnswers(a => ({ ...a, [qid]: value }))
    setActionError('')
    if (immediate) { void persist(qid); return }
    setQState(qid, 'pending')
    window.clearTimeout(timers.current[qid])
    timers.current[qid] = window.setTimeout(() => void persist(qid), TEXT_DEBOUNCE_MS)
  }

  const flush = useCallback(async () => {
    Object.keys(timers.current).forEach(qid => void persist(qid))
    await Promise.allSettled(Object.values(queues.current))
    return Object.entries(stateRef.current).filter(([, s]) => s !== 'saved').map(([qid]) => qid)
  }, [persist])

  // Retry answers that failed while offline as soon as the connection returns.
  useEffect(() => {
    if (online) Object.entries(stateRef.current).filter(([, st]) => st === 'error').forEach(([qid]) => void persist(qid))
  }, [online, persist])

  // Save any typed answer that is still waiting when the page is left.
  useEffect(() => () => { Object.keys(timers.current).forEach(qid => void persist(qid)) }, [persist])
  useEffect(() => {
    const warn = (e: BeforeUnloadEvent) => { if (Object.values(stateRef.current).some(s => s !== 'saved')) e.preventDefault() }
    window.addEventListener('beforeunload', warn)
    return () => window.removeEventListener('beforeunload', warn)
  }, [])

  async function submit() {
    if (submittingRef.current) return // ignore repeated clicks before the disabled state renders
    submittingRef.current = true
    setSubmitting(true); setActionError('')
    try {
      const unsaved = await flush()
      if (unsaved.length) { setActionError(tt('Còn {0} câu chưa lưu được. Hãy thử lưu lại trước khi nộp.', { 0: unsaved.length })); setConfirming(false); return }
      await examApi.submit(id!)
      goResult()
    } catch (e) {
      setActionError(errorText(e))
      setConfirming(false)
      void sync()
    } finally {
      submittingRef.current = false
      setSubmitting(false)
    }
  }

  function goTo(i: number) {
    const q = session?.questions[index]
    if (q && timers.current[q.questionId]) void persist(q.questionId)
    setIndex(i); setShowPalette(false)
    requestAnimationFrame(() => headingRef.current?.focus())
  }

  if (loadError) return <main className="auth-shell"><div className="card" style={{ width: 'min(520px, 100%)' }}><ErrorState error={loadError} onRetry={() => { setLoadError(null); loadedFor.current = id ?? null; void sync(true) }} /></div></main>
  if (!session) return <main className="auth-shell"><p role="status" className="row muted"><Spinner /> {t('Đang tải bài thi…')}</p></main>
  if (autoSubmitted) return (
    <main className="auth-shell">
      <section className="card card-body stack" style={{ width: 'min(560px, 100%)' }} aria-labelledby="auto-submitted-title">
        <h1 id="auto-submitted-title" className="row" style={{ fontSize: '1.4rem', gap: 10 }}><ShieldAlert size={22} aria-hidden /> {t('Bài đã được tự động nộp')}</h1>
        <p>{t('Lượt làm bài này đã vượt ngưỡng {limit} tín hiệu trình duyệt của kỳ thi nên hệ thống đã nộp các câu trả lời đã lưu.', { limit: signals?.limit ?? '' })}</p>
        <p className="small muted">{t('Tín hiệu trình duyệt là dữ liệu ghi nhận, không phải kết luận gian lận. Nếu cần làm lại, hãy liên hệ giáo viên phụ trách kỳ thi.')}</p>
        <div className="row">
          <button type="button" className="btn btn-primary" onClick={goResult}>{t('Xem kết quả')}</button>
          <button type="button" className="btn btn-secondary" onClick={() => navigate(`/exam/lobby/${session.examId}`, { replace: true })}>{t('Về phòng chờ')}</button>
        </div>
      </section>
    </main>
  )

  const questions = session.questions
  const q = questions[index]
  const answered = (qq: SafeQuestion) => (answers[qq.questionId] ?? '').trim() !== ''
  const answeredCount = questions.filter(answered).length
  const unanswered = questions.map((qq, i) => (answered(qq) ? -1 : i)).filter(i => i >= 0)
  const states = Object.values(saveState)
  const errors = states.filter(s => s === 'error').length
  const busy = states.some(s => s === 'saving' || s === 'pending')
  const level = remaining <= 60 ? 'critical' : remaining <= 300 ? 'warn' : 'normal'
  const blocked = integrity.loading || integrity.needsFullscreen || integrity.ack
  const locked = expired || submitting || blocked
  const lastLine = signals?.stage === 'last' ? <p><strong>{t('Thêm một tín hiệu nữa, bài sẽ được tự động nộp.')}</strong></p> : null
  const countLine = signals ? <p><strong>{t('Đã ghi nhận {count}/{limit} tín hiệu rời khỏi bài.', { count: signals.count, limit: signals.limit })}</strong></p> : null
  const neutral = <p className="small muted">{t('Tín hiệu trình duyệt là dữ liệu ghi nhận, không phải kết luận gian lận. Đồng hồ vẫn chạy theo máy chủ; hết giờ bài được tự động nộp như bình thường.')}</p>

  return (
    <div className="take-shell">
      <div className="sr-only" aria-live="assertive">{announcement}</div>
      <header className="take-bar">
        <div style={{ flex: 1, minWidth: 180 }}>
          <div style={{ color: 'var(--color-ink)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{session.title}</div>
          <div className="xsmall muted">{tt('Lượt')} {session.attemptNumber} {tt('· Đã trả lời')} {answeredCount}/{questions.length}{integrity.policy && tt(' · Có ghi nhận tín hiệu trình duyệt')}</div>
        </div>
        <SaveIndicator online={online} errors={errors} busy={busy} lastSavedAt={lastSavedAt} />
        <span className="timer" role="timer" aria-label={tt('Thời gian còn lại {0}', { 0: formatDuration(remaining) })} data-level={level}>
          <Timer size={16} aria-hidden /> {formatDuration(remaining)}
        </span>
        <button type="button" className="btn btn-primary" disabled={locked} onClick={() => setConfirming(true)}><Send size={16} aria-hidden /> {tt('Nộp bài')}</button>
      </header>

      {/* Questions render only while not blocked (fullscreen active, leave acknowledged): contract A. */}
      {!blocked && <>
      <div className="take-body">
        <main className="stack">
          {expired && <Notice tone="warning" live title={t('Đã hết giờ')}>{t('Hệ thống đang tự động nộp các câu trả lời đã lưu. Bạn sẽ được chuyển đến trang kết quả.')}</Notice>}
          {!online && <Notice tone="warning" live title={t('Mất kết nối')}>{t('Câu trả lời sẽ được lưu khi có mạng lại. Đồng hồ vẫn chạy trên máy chủ; giữ trang này mở.')}</Notice>}
          {actionError && <Notice tone="danger" live>{actionError}</Notice>}
          {signals && signals.stage !== 'none' && (signals.stage === 'last' || integrity.policy?.warningPolicy === 'WARN_ONLY') && (
            <Notice tone="warning" live><strong>{t('Đã ghi nhận {count}/{limit} tín hiệu rời khỏi bài.', { count: signals.count, limit: signals.limit })}</strong>{' '}
              {signals.stage === 'last' ? t('Thêm một tín hiệu nữa, bài sẽ được tự động nộp.') : t('Vượt quá {limit} tín hiệu, bài sẽ được tự động nộp.', { limit: signals.limit })}</Notice>)}

          <section className="card card-body stack-lg" aria-labelledby="question-heading">
            <div className="spread">
              <h1 id="question-heading" ref={headingRef} tabIndex={-1} style={{ fontSize: '1rem' }}>{tt('Câu')} {index + 1} / {questions.length}</h1>
              <span className="xsmall muted">{QUESTION_TYPE_LABEL[q.questionType]} · {formatScore(q.points)} {tt('điểm')}</span>
            </div>
            <p className="question-text">{q.content}</p>
            <Answer question={q} value={answers[q.questionId] ?? ''} disabled={locked}
              onChange={(v, immediate) => change(q.questionId, v, immediate)} onBlur={() => { if (timers.current[q.questionId]) void persist(q.questionId) }} />
            {saveState[q.questionId] === 'error' && (
              <div className="row"><span className="field-error">{tt('Câu này chưa lưu được.')}</span>
                <button type="button" className="btn btn-secondary btn-sm" onClick={() => void persist(q.questionId)}>{tt('Thử lưu lại')}</button></div>
            )}
          </section>

          <nav className="take-nav" aria-label={tt('Chuyển câu')}>
            <button type="button" className="btn btn-secondary" disabled={index === 0} onClick={() => goTo(index - 1)}><ChevronLeft size={16} aria-hidden /> {tt('Câu trước')}</button>
            <button type="button" className="btn btn-ghost hide-desktop" aria-expanded={showPalette} onClick={() => setShowPalette(v => !v)}><LayoutGrid size={16} aria-hidden /> {tt('Các câu')}</button>
            {index < questions.length - 1
              ? <button type="button" className="btn btn-secondary" onClick={() => goTo(index + 1)}>{tt('Câu sau')} <ChevronRight size={16} aria-hidden /></button>
              : <button type="button" className="btn btn-primary" disabled={locked} onClick={() => setConfirming(true)}>{tt('Xem lại và nộp')}</button>}
          </nav>
        </main>

        <aside className={`card card-body stack sticky-pane palette-pane ${showPalette ? 'open' : ''}`} aria-label={tt('Danh sách câu hỏi')} style={{ top: 76 }}>
          <div className="spread"><h2 style={{ fontSize: '0.9375rem' }}>{tt('Câu hỏi')}</h2><span className="xsmall muted">{answeredCount}/{questions.length} {tt('đã trả lời')}</span></div>
          <div className="palette">
            {questions.map((qq, i) => (
              <button key={qq.questionId} type="button" data-answered={answered(qq)} aria-current={i === index}
                aria-label={tt('Câu {0}, {1}{2}', { 0: i + 1, 1: answered(qq) ? 'đã trả lời' : 'chưa trả lời', 2: saveState[qq.questionId] === 'error' ? ', chưa lưu được' : '' })}
                onClick={() => goTo(i)}>{i + 1}</button>
            ))}
          </div>
          <div className="xsmall muted stack-sm" style={{ gap: 4 }}>
            <span className="row" style={{ gap: 6 }}><span aria-hidden style={{ width: 12, height: 12, background: 'var(--color-accent)', border: '1px solid var(--color-accent)' }} /> {tt('Đã trả lời')}</span>
            <span className="row" style={{ gap: 6 }}><span aria-hidden style={{ width: 12, height: 12, border: '1px solid #000' }} /> {tt('Chưa trả lời')}</span>
          </div>
        </aside>
      </div>
      </>}

      {integrity.needsFullscreen && !integrity.supported && (
        <BlockingOverlay title={t('Trình duyệt không hỗ trợ toàn màn hình')} remaining={remaining}
          actions={<button type="button" className="btn btn-secondary" onClick={() => navigate(`/exam/lobby/${session.examId}`)}>{t('Về phòng chờ')}</button>}>
          <p>{t('Kỳ thi này yêu cầu làm bài ở chế độ toàn màn hình nhưng trình duyệt hiện tại không có chức năng này. Hãy mở bài bằng trình duyệt máy tính bản mới (Chrome, Edge, Firefox hoặc Safari).')}</p>
          {neutral}
        </BlockingOverlay>
      )}
      {integrity.needsFullscreen && integrity.supported && (
        <BlockingOverlay title={t(wasFullscreen.current ? 'Bạn đã thoát toàn màn hình' : 'Bật toàn màn hình để làm bài')} remaining={remaining}
          actions={<button type="button" className="btn btn-primary" data-autofocus onClick={() => void backToFullscreen()}><Maximize size={16} aria-hidden /> {t('Quay lại toàn màn hình')}</button>}>
          <p>{t('Kỳ thi yêu cầu làm bài ở chế độ toàn màn hình. Câu hỏi được ẩn và không sửa được câu trả lời cho đến khi bạn quay lại.')}</p>
          {wasFullscreen.current && countLine}{lastLine}
          {fsRefused && <Notice tone="danger" live>{t('Trình duyệt chưa bật được toàn màn hình. Bấm “Quay lại toàn màn hình” để thử lại.')}</Notice>}
          {neutral}
        </BlockingOverlay>
      )}
      {!integrity.needsFullscreen && integrity.ack && (
        <BlockingOverlay title={t('Bạn vừa rời khỏi trang làm bài')} remaining={remaining}
          actions={<button type="button" className="btn btn-primary" data-autofocus onClick={integrity.acknowledge}>{t('Tôi đã hiểu')}</button>}>
          {countLine ?? <p>{t('Đang ghi nhận tín hiệu…')}</p>}{lastLine}
          {neutral}
        </BlockingOverlay>
      )}
      {confirming && (
        <ConfirmDialog title={tt('Nộp bài?')} confirmLabel={tt('Nộp bài')} busy={submitting} cancelLabel={tt('Tiếp tục làm bài')}
          onConfirm={() => void submit()} onCancel={() => setConfirming(false)}>
          <div className="stack">
            <div className="grid-stats">
              <div className="card stat"><div className="stat-label">{tt('Đã trả lời')}</div><div className="stat-value">{answeredCount}</div></div>
              <div className="card stat"><div className="stat-label">{tt('Chưa trả lời')}</div><div className="stat-value">{unanswered.length}</div></div>
            </div>
            {unanswered.length > 0 && (
              <div className="stack-sm">
                <span className="small">{tt('Câu chưa trả lời:')}</span>
                <div className="row">{unanswered.map(i => <button key={i} type="button" className="btn btn-secondary btn-sm" onClick={() => { setConfirming(false); goTo(i) }}>{tt('Câu')} {i + 1}</button>)}</div>
              </div>
            )}
            {busy && <p className="small muted">{tt('Đang lưu các thay đổi cuối; hệ thống sẽ hoàn tất lưu trước khi nộp.')}</p>}
            <Notice tone="warning">{tt('Sau khi nộp, bạn không thể thay đổi câu trả lời.')}</Notice>
          </div>
        </ConfirmDialog>
      )}
    </div>
  )
}

function SaveIndicator({ online, errors, busy, lastSavedAt }: { online: boolean; errors: number; busy: boolean; lastSavedAt: Date | null }) {
  useT()
  let content
  if (!online) content = <><CloudOff size={16} aria-hidden /> {tt('Mất mạng')}</>
  else if (errors) content = <span style={{ color: 'var(--color-danger)' }} className="row"><AlertCircle size={16} aria-hidden /> {errors} {tt('câu chưa lưu')}</span>
  else if (busy) content = <><Spinner size={14} /> {tt('Đang lưu…')}</>
  else if (lastSavedAt) content = <><CheckCircle2 size={16} aria-hidden style={{ color: 'var(--color-success)' }} /> {tt('Đã lưu')} {formatClock(lastSavedAt)}</>
  else content = <>{tt('Câu trả lời được lưu tự động')}</>
  return <span role="status" className="row xsmall muted" style={{ gap: 6 }}>{content}</span>
}

function Answer({ question, value, disabled, onChange, onBlur }: {
  question: SafeQuestion; value: string; disabled: boolean; onChange: (v: string, immediate: boolean) => void; onBlur: () => void
}) {
  useT()
  const name = `answer-${question.questionId}`
  if (question.questionType === 'MULTIPLE_CHOICE' || question.questionType === 'TRUE_FALSE') {
    return (
      <fieldset className="stack-sm">
        <legend className="sr-only">{tt('Chọn một đáp án')}</legend>
        {question.options.map((o, i) => (
          <label key={o.id} className="choice" style={{ alignItems: 'center' }}>
            <input type="radio" name={name} value={o.id} checked={value === o.id} disabled={disabled} onChange={() => onChange(o.id, true)} className="sr-only" />
            <span className="option-key" aria-hidden>{String.fromCharCode(65 + i)}</span>
            <span className="prewrap" style={{ flex: 1 }}>{o.content}</span>
          </label>
        ))}
        {value && !disabled && <div><button type="button" className="btn btn-ghost btn-sm" onClick={() => onChange('', true)}>{tt('Bỏ chọn')}</button></div>}
      </fieldset>
    )
  }
  if (question.questionType === 'SHORT_ANSWER') {
    return (
      <div className="field">
        <label className="field-label" htmlFor={name}>{tt('Câu trả lời')}</label>
        <input id={name} className="input" value={value} disabled={disabled} maxLength={20000} autoComplete="off"
          onChange={e => onChange(e.target.value, false)} onBlur={onBlur} />
        <span className="field-hint">{tt('Nhập chính xác câu trả lời ngắn gọn.')}</span>
      </div>
    )
  }
  return (
    <div className="field">
      <label className="field-label" htmlFor={name}>{tt('Bài làm')}</label>
      <textarea id={name} className="input" rows={10} value={value} disabled={disabled} maxLength={20000}
        onChange={e => onChange(e.target.value, false)} onBlur={onBlur} />
      <span className="field-hint">{value.length.toLocaleString('vi-VN')}{tt('/20.000 ký tự. Giáo viên sẽ chấm phần tự luận.')}</span>
    </div>
  )
}

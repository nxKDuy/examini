import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { CalendarClock, ExternalLink, FileCheck2, Lock, PlayCircle, RotateCcw, Unlock, X } from 'lucide-react'
import './calendar.css'
import type { CalendarItem } from './calendarApi'
import { countdownLine, isManager, isTeacherItem, reasonText, relationLabel, RULE_HELP, ruleLabel, toMs, windowText } from './calendarModel'
import { StateBadge } from './ExamCard'
import FollowButton from './FollowButton'
import LifecycleDialog, { type LifecycleKind } from './LifecycleDialog'
import ScheduleDialog from './ScheduleDialog'
import { Notice } from '../../components/ui'
import { useModalFocus } from '../../components/useModalFocus'
import { examApi } from '../exams/examApi'
import { errorText } from '../../lib/errors'
import { formatDateTimeIn, zoneLabel } from '../../lib/zone'
import { EXAM_STATUS } from '../../components/statusDefs'

/** Right-hand panel on desktop, bottom sheet on narrow screens. Every action is a backend call; the buttons only mirror its answers. */
export default function DetailPanel({ item, nowMs, viewerTz, narrow, onClose }: { item: CalendarItem; nowMs: number; viewerTz: string; narrow: boolean; onClose: () => void }) {
  const navigate = useNavigate()
  const qc = useQueryClient()
  const headingRef = useRef<HTMLHeadingElement>(null)
  const panelRef = useRef<HTMLElement>(null)
  const [schedule, setSchedule] = useState(false)
  const [lifecycle, setLifecycle] = useState<LifecycleKind | null>(null)
  const [message, setMessage] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const teacher = isTeacherItem(item), manage = isManager(item)
  const w = windowText(item, viewerTz)
  const countdown = countdownLine(item, nowMs)
  // Start/Join reasons describe the Student's own eligibility; managers and proctors do not take the Exam.
  const reason = !teacher && !item.canStart && !item.canResume ? reasonText(item.reasonCode) : null

  // Bottom sheet (narrow): a real modal - Tab stays inside, background scroll is locked, Escape closes (only when no dialog is above),
  // and focus returns to the opener. Side panel (wide): not modal, but it still takes focus on open, closes on Escape and gives focus back.
  useModalFocus(panelRef, { trap: narrow, escape: true, onEscape: onClose, initialFocus: () => headingRef.current })
  useEffect(() => { headingRef.current?.focus() }, [item.examId])

  const join = useMutation({
    mutationFn: () => examApi.join(item.examId),
    onSuccess: async () => { await Promise.all(['calendar', 'my-exams', 'availability'].map(k => qc.invalidateQueries({ queryKey: [k] }))); navigate(`/exam/lobby/${item.examId}`) },
    onError: e => setMessage({ tone: 'danger', text: errorText(e) }),
  })

  const examPath = teacher ? `/exams/${item.examId}` : `/exam/lobby/${item.examId}`
  const sessionActive = item.latestSessionStatus === 'IN_PROGRESS' && item.latestSessionId
  const sessionDone = item.latestSessionId && item.latestSessionStatus && item.latestSessionStatus !== 'IN_PROGRESS'
  const endMs = toMs(item.endAt)

  const body = (
    <>
      <div className="cal-panel-head">
        <div style={{ minWidth: 0 }}>
          <h2 ref={headingRef} tabIndex={-1} id="cal-panel-title" style={{ overflowWrap: 'anywhere' }}>{item.examTitle}</h2>
          <div className="row" style={{ marginTop: 8 }}><StateBadge state={item.calendarState} /><span className="small muted">{relationLabel(item.relation)}</span></div>
        </div>
        <button type="button" className="btn btn-ghost btn-icon" aria-label="Đóng chi tiết" onClick={onClose}><X size={18} /></button>
      </div>
      <div className="cal-panel-body">
        {message && <Notice tone={message.tone} live>{message.text}</Notice>}
        <dl className="meta" style={{ gridTemplateColumns: 'minmax(0, 1fr)' }}>
          <div><dt>Thời gian (giờ của kỳ thi)</dt><dd style={{ fontSize: '1rem' }}>{w.primary}<br /><span className="muted">{w.zone}</span></dd></div>
          {w.mineText && <div><dt>Giờ của bạn</dt><dd style={{ fontSize: '1rem' }}>{w.mineText}<br /><span className="muted">{w.mineZone} · {viewerTz}</span></dd></div>}
          {countdown && <div><dt>Đếm ngược</dt><dd>{countdown}</dd></div>}
          <div><dt>Thời lượng làm bài</dt><dd>{item.durationMinutes} phút</dd></div>
          <div><dt>Cách tính hạn nộp</dt><dd style={{ fontSize: '1rem' }}>{ruleLabel(item.deadlineRule)}<br /><span className="xsmall muted" style={{ fontWeight: 400 }}>{RULE_HELP[item.deadlineRule]}</span></dd></div>
          {teacher
            ? <>
                <div><dt>Trạng thái đề</dt><dd style={{ fontSize: '1rem' }}>{EXAM_STATUS[item.status]?.label ?? item.status}</dd></div>
                {item.participantCount !== null && <div><dt>Người tham gia</dt><dd>{item.participantCount}{item.maxParticipants !== null ? ` / ${item.maxParticipants}` : ''}</dd></div>}
              </>
            : <>
                {item.attemptsRemaining !== null && <div><dt>Lượt còn lại</dt><dd>{item.attemptsRemaining}</dd></div>}
                {item.effectiveExpiresAt && <div><dt>Hạn nộp lượt đang làm</dt><dd style={{ fontSize: '1rem' }}>{formatDateTimeIn(Date.parse(item.effectiveExpiresAt), item.timezone)} {zoneLabel(Date.parse(item.effectiveExpiresAt), item.timezone)}</dd></div>}
              </>}
        </dl>
        {reason && <Notice tone="info">{reason}</Notice>}
        {!teacher && item.calendarState === 'MISSED' && <Notice tone="danger">Kỳ thi đã kết thúc khi bạn chưa làm bài.</Notice>}
        {!teacher && item.followed && !item.participant && <Notice tone="info">Bạn đang theo dõi kỳ thi này: nó nằm trong Lịch và bạn nhận thông báo. Chưa tham gia nên chưa làm bài được.</Notice>}
        {teacher && !manage && <Notice tone="info">Giám thị chỉ xem lịch; không sửa lịch hay mở/đóng kỳ thi.</Notice>}

        <div className="stack-sm">
          {teacher ? (
            <>
              {manage && <button type="button" className="btn btn-primary" onClick={() => setSchedule(true)}><CalendarClock size={16} aria-hidden /> {item.status === 'CLOSED' ? 'Xem lịch' : 'Sửa lịch'}</button>}
              {manage && item.status === 'PUBLISHED' && <button type="button" className="btn btn-secondary" onClick={() => setLifecycle('open')}><Unlock size={16} aria-hidden /> Mở đề</button>}
              {manage && (item.status === 'OPEN' || item.status === 'PUBLISHED') && <button type="button" className="btn btn-secondary" onClick={() => setLifecycle('close')}><Lock size={16} aria-hidden /> Đóng đề</button>}
              {manage && item.status === 'CLOSED' && <button type="button" className="btn btn-secondary" onClick={() => setLifecycle('reopen')}><RotateCcw size={16} aria-hidden /> Mở lại</button>}
            </>
          ) : (
            <>
              {sessionActive && item.canResume !== false && <Link to={`/exam/take/${item.latestSessionId}`} className="btn btn-primary"><PlayCircle size={16} aria-hidden /> Tiếp tục làm bài</Link>}
              {item.canJoin && !item.participant && <button type="button" className="btn btn-primary" disabled={join.isPending} onClick={() => join.mutate()}>{join.isPending ? 'Đang tham gia…' : 'Tham gia kỳ thi'}</button>}
              {!sessionActive && <Link to={examPath} className={`btn ${item.canStart ? 'btn-primary' : 'btn-secondary'}`}><PlayCircle size={16} aria-hidden /> {item.participant ? (item.canStart ? 'Vào phòng chờ để bắt đầu' : 'Vào phòng chờ') : 'Xem trước'}</Link>}
              {sessionDone && <Link to={`/exam/result/${item.latestSessionId}`} className="btn btn-secondary"><FileCheck2 size={16} aria-hidden /> Xem kết quả</Link>}
              {(!item.participant || item.followed) && <FollowButton examId={item.examId} followed={item.followed} size="md" onError={text => setMessage({ tone: 'danger', text })} />}
            </>
          )}
          <Link to={examPath} className="btn btn-ghost"><ExternalLink size={16} aria-hidden /> Xem đầy đủ</Link>
        </div>
      </div>
      {schedule && <ScheduleDialog examId={item.examId} examTitle={item.examTitle} status={item.status} durationMinutes={item.durationMinutes} onClose={() => setSchedule(false)}
        onSaved={() => setMessage({ tone: 'success', text: 'Đã lưu lịch.' })} />}
      {lifecycle && <LifecycleDialog kind={lifecycle} examId={item.examId} examTitle={item.examTitle} endAtMs={endMs} nowMs={nowMs} onCancel={() => setLifecycle(null)}
        onDone={kind => { setLifecycle(null); setMessage({ tone: 'success', text: { open: 'Đã mở kỳ thi.', close: 'Đã đóng kỳ thi.', reopen: 'Đã mở lại kỳ thi.' }[kind] }) }} />}
    </>
  )

  return (
    <>
      {narrow && <button type="button" className="cal-sheet-backdrop" aria-label="Đóng chi tiết" tabIndex={-1} onClick={onClose} />}
      <aside ref={panelRef} className="cal-panel" tabIndex={-1} role={narrow ? 'dialog' : 'complementary'} aria-modal={narrow ? true : undefined} aria-labelledby="cal-panel-title">{body}</aside>
    </>
  )
}

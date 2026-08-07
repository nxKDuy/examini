import { useState, useEffect, useCallback } from 'react'
import { useParams, useNavigate } from 'react-router-dom'
import { Flag, ChevronLeft, ChevronRight, Send, AlertTriangle, Clock } from 'lucide-react'

// ─── Mock Data ────────────────────────────────────────────────────────────────
const EXAM_DATA = {
  id: '1',
  title: 'Kiểm tra Giữa kỳ Môn Toán 12',
  durationMinutes: 45,
  questions: [
    { id: 'q1', type: 'MULTIPLE_CHOICE', content: 'Vận tốc ánh sáng trong chân không là bao nhiêu?', options: ['300,000 km/s', '150,000 km/s', '3,000 km/s', '30,000 km/s'] },
    { id: 'q2', type: 'MULTIPLE_CHOICE', content: 'Ai là người phát minh ra thuyết tương đối?', options: ['Isaac Newton', 'Albert Einstein', 'Galileo Galilei', 'Nikola Tesla'] },
    { id: 'q3', type: 'MULTIPLE_CHOICE', content: 'Đơn vị đo cường độ dòng điện là gì?', options: ['Volt (V)', 'Watt (W)', 'Ampere (A)', 'Ohm (Ω)'] },
    { id: 'q4', type: 'MULTIPLE_CHOICE', content: 'Điện trở của một dây dẫn phụ thuộc vào yếu tố nào sau đây?', options: ['Chỉ chiều dài', 'Chỉ tiết diện', 'Chiều dài, tiết diện và điện trở suất', 'Khối lượng dây dẫn'] },
    { id: 'q5', type: 'MULTIPLE_CHOICE', content: 'Công thức tính công suất điện là?', options: ['P = U/I', 'P = U·I', 'P = I²/R', 'P = U + I'] },
    { id: 'q6', type: 'ESSAY', content: 'Giải thích hiện tượng giao thoa ánh sáng và nêu một ví dụ thực tế bạn đã quan sát được.' },
    { id: 'q7', type: 'MULTIPLE_CHOICE', content: 'Định luật bảo toàn năng lượng phát biểu rằng:', options: ['Năng lượng có thể tạo ra từ không khí', 'Năng lượng chỉ có thể chuyển hóa, không mất đi', 'Năng lượng luôn bị hao phí', 'Tổng năng lượng luôn tăng'] },
    { id: 'q8', type: 'MULTIPLE_CHOICE', content: 'Sóng âm là loại sóng gì?', options: ['Sóng ngang', 'Sóng dọc', 'Sóng điện từ', 'Sóng ánh sáng'] },
  ],
}

// ─── Types ────────────────────────────────────────────────────────────────────
type QuestionState = 'unanswered' | 'answered' | 'flagged'

// ─── Helpers ─────────────────────────────────────────────────────────────────
function formatTime(seconds: number) {
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${m.toString().padStart(2, '0')}:${s.toString().padStart(2, '0')}`
}

// ─── Submit Confirmation Modal ────────────────────────────────────────────────
function SubmitModal({ unanswered, flagged, onConfirm, onCancel, submitting }:
  { unanswered: number; flagged: number; onConfirm: () => void; onCancel: () => void; submitting: boolean }) {
  return (
    <div style={{
      position: 'fixed', inset: 0, zIndex: 1000,
      background: 'rgba(0,0,0,0.5)', backdropFilter: 'blur(4px)',
      display: 'flex', alignItems: 'center', justifyContent: 'center', padding: 16,
    }}>
      <div className="clean-card page-enter" style={{ width: '100%', maxWidth: 440, padding: '32px', textAlign: 'center' }}>
        <div style={{ width: 56, height: 56, borderRadius: '50%', background: 'rgba(240,78,56,0.1)', display: 'flex', alignItems: 'center', justifyContent: 'center', margin: '0 auto 20px' }}>
          <Send size={24} color="var(--color-primary-500)" />
        </div>

        <h2 style={{ fontSize: '1.3rem', fontWeight: 700, color: 'var(--color-surface-50)', marginBottom: 12 }}>
          Xác nhận nộp bài
        </h2>

        {(unanswered > 0 || flagged > 0) ? (
          <div style={{ padding: '12px 16px', borderRadius: 8, background: 'rgba(245,158,11,0.08)', border: '1px solid rgba(245,158,11,0.3)', marginBottom: 20, textAlign: 'left' }}>
            <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start' }}>
              <AlertTriangle size={16} color="#f59e0b" style={{ flexShrink: 0, marginTop: 2 }} />
              <div style={{ fontSize: '0.875rem', color: 'var(--color-surface-200)', lineHeight: 1.6 }}>
                {unanswered > 0 && <div>• <strong>{unanswered} câu</strong> chưa có đáp án.</div>}
                {flagged > 0 && <div>• <strong>{flagged} câu</strong> đang cắm cờ xem lại.</div>}
              </div>
            </div>
          </div>
        ) : (
          <p style={{ color: 'var(--color-surface-400)', fontSize: '0.9rem', marginBottom: 20 }}>
            Bạn đã hoàn thành tất cả câu hỏi. Chắc chắn muốn nộp bài?
          </p>
        )}

        <div style={{ display: 'flex', gap: 12 }}>
          <button onClick={onCancel} className="btn-secondary" style={{ flex: 1, height: 44 }} disabled={submitting}>
            Kiểm tra lại
          </button>
          <button onClick={onConfirm} className="btn-primary" style={{ flex: 1, height: 44 }} disabled={submitting}>
            {submitting ? 'Đang nộp...' : 'Nộp bài'}
          </button>
        </div>
      </div>
    </div>
  )
}

// ─── Main Component ───────────────────────────────────────────────────────────
export default function StudentExamPage() {
  const { id } = useParams()
  const navigate = useNavigate()

  // State
  const [timeLeft, setTimeLeft] = useState(EXAM_DATA.durationMinutes * 60)
  const [currentIdx, setCurrentIdx] = useState(0)
  const [answers, setAnswers] = useState<Record<string, string>>({})
  const [flagged, setFlagged] = useState<Set<string>>(new Set())
  const [showModal, setShowModal] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [submitted, setSubmitted] = useState(false)
  const [frozen, setFrozen] = useState(false) // auto-submit lock

  const questions = EXAM_DATA.questions
  const total = questions.length
  const currentQ = questions[currentIdx]
  const isWarning = timeLeft < 300 // < 5 minutes

  // ─── Auto-submit on timeout ────────────────────────────────────────────────
  const doSubmit = useCallback(() => {
    if (submitted) return
    setFrozen(true)
    setSubmitting(true)
    setTimeout(() => {
      setSubmitting(false)
      setSubmitted(true)
      navigate(`/exam/result/${id}`)
    }, 1500)
  }, [submitted, id, navigate])

  // Countdown timer
  useEffect(() => {
    if (submitted || frozen) return
    const timer = setInterval(() => {
      setTimeLeft(t => {
        if (t <= 1) { clearInterval(timer); doSubmit(); return 0 }
        return t - 1
      })
    }, 1000)
    return () => clearInterval(timer)
  }, [submitted, frozen, doSubmit])

  // Question state helper
  const getQState = (qId: string): QuestionState => {
    if (flagged.has(qId)) return 'flagged'
    if (answers[qId] !== undefined) return 'answered'
    return 'unanswered'
  }

  const toggleFlag = () => {
    setFlagged(prev => {
      const next = new Set(prev)
      if (next.has(currentQ.id)) next.delete(currentQ.id)
      else next.add(currentQ.id)
      return next
    })
  }

  const selectAnswer = (val: string) => {
    if (frozen) return
    setAnswers(prev => ({ ...prev, [currentQ.id]: val }))
  }

  const unansweredCount = questions.filter(q => answers[q.id] === undefined).length
  const flaggedCount = flagged.size
  const answeredCount = Object.keys(answers).length
  const progress = Math.round((answeredCount / total) * 100)

  // ─── Submitted state ───────────────────────────────────────────────────────
  if (submitted) {
    return (
      <div style={{ minHeight: '100dvh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#F9F9F9' }}>
        <div className="clean-card page-enter" style={{ padding: 48, textAlign: 'center', maxWidth: 420 }}>
          <div style={{ width: 64, height: 64, borderRadius: '50%', background: 'rgba(16,185,129,0.12)', display: 'flex', alignItems: 'center', justifyContent: 'center', margin: '0 auto 20px' }}>
            <Send size={28} color="#10b981" />
          </div>
          <h1 style={{ fontSize: '1.5rem', fontWeight: 700, color: 'var(--color-surface-50)', marginBottom: 12 }}>Nộp bài thành công!</h1>
          <p style={{ color: 'var(--color-surface-400)', lineHeight: 1.6, marginBottom: 28, fontSize: '0.9rem' }}>
            Hệ thống đã ghi nhận bài làm của bạn. Điểm trắc nghiệm được chấm tự động. Vui lòng chờ giáo viên công bố kết quả.
          </p>
          <button className="btn-primary" style={{ width: '100%' }} onClick={() => navigate('/dashboard')}>
            Về bảng điều khiển
          </button>
        </div>
      </div>
    )
  }

  return (
    <div style={{ minHeight: '100dvh', background: '#F9F9F9', display: 'flex', flexDirection: 'column' }}>

      {/* ── Sticky Header ─────────────────────────────────────────────────── */}
      <header style={{
        position: 'sticky', top: 0, zIndex: 50,
        background: '#ffffff',
        borderBottom: `2px solid ${isWarning ? 'var(--color-primary-500)' : 'var(--color-surface-700)'}`,
        boxShadow: '0 2px 8px rgba(0,0,0,0.06)',
      }}>
        {/* Progress bar */}
        <div style={{ height: 3, background: 'var(--color-surface-800)' }}>
          <div style={{
            height: '100%', background: 'var(--color-primary-500)',
            width: `${progress}%`, transition: 'width 0.3s ease',
          }} />
        </div>

        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0 24px', height: 60 }}>
          {/* Exam title */}
          <div style={{ fontWeight: 600, color: 'var(--color-surface-50)', fontSize: '0.95rem',
            overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: '35%' }}>
            {EXAM_DATA.title}
          </div>

          {/* Countdown */}
          <div style={{
            display: 'flex', alignItems: 'center', gap: 8,
            padding: '6px 16px', borderRadius: 8,
            background: isWarning ? 'rgba(240,78,56,0.08)' : 'var(--color-surface-800)',
            border: `1px solid ${isWarning ? 'var(--color-primary-400)' : 'var(--color-surface-700)'}`,
            color: isWarning ? 'var(--color-primary-600)' : 'var(--color-surface-50)',
            fontWeight: 800, fontSize: '1.35rem', letterSpacing: '0.04em',
            fontVariantNumeric: 'tabular-nums',
            animation: isWarning && timeLeft % 2 === 0 ? 'heartbeat 1s ease-in-out infinite' : 'none',
          }}>
            <Clock size={18} />
            {formatTime(timeLeft)}
          </div>

          {/* Submit */}
          <button className="btn-primary" onClick={() => setShowModal(true)} disabled={frozen}
            style={{ height: 38, fontSize: '0.875rem', paddingInline: 20 }}>
            <Send size={14} /> Nộp bài
          </button>
        </div>
      </header>

      {/* ── Main Area ─────────────────────────────────────────────────────── */}
      <div style={{ flex: 1, display: 'flex', gap: 0 }}>

        {/* Question Area */}
        <main style={{ flex: 1, padding: '32px 40px', display: 'flex', flexDirection: 'column', minWidth: 0 }}>

          {/* Question header row */}
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 20 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <span style={{
                fontWeight: 700, fontSize: '0.85rem', letterSpacing: '0.05em', textTransform: 'uppercase',
                color: 'var(--color-surface-400)',
              }}>
                Câu {currentIdx + 1} / {total}
              </span>
              {getQState(currentQ.id) === 'answered' && (
                <span style={{ fontSize: '0.75rem', color: 'var(--color-success)', background: 'rgba(16,185,129,0.1)', padding: '2px 8px', borderRadius: 999, fontWeight: 600 }}>
                  ✓ Đã trả lời
                </span>
              )}
            </div>

            {/* Flag button */}
            <button
              onClick={toggleFlag}
              style={{
                display: 'flex', alignItems: 'center', gap: 6, padding: '6px 12px', borderRadius: 6, border: '1px solid',
                borderColor: flagged.has(currentQ.id) ? 'var(--color-warning)' : 'var(--color-surface-700)',
                background: flagged.has(currentQ.id) ? 'rgba(245,158,11,0.08)' : 'transparent',
                color: flagged.has(currentQ.id) ? '#f59e0b' : 'var(--color-surface-400)',
                cursor: 'pointer', fontSize: '0.8rem', fontWeight: 600, transition: 'all 0.15s',
              }}>
              <Flag size={14} /> {flagged.has(currentQ.id) ? 'Đã cắm cờ' : 'Đánh dấu xem lại'}
            </button>
          </div>

          {/* Question card */}
          <div className="clean-card" style={{ padding: '32px', flex: 1, display: 'flex', flexDirection: 'column' }}>
            <p style={{ fontSize: '1.15rem', fontWeight: 500, color: 'var(--color-surface-50)', lineHeight: 1.7, marginBottom: 28 }}>
              {currentQ.content}
            </p>

            {/* Multiple choice */}
            {currentQ.type === 'MULTIPLE_CHOICE' && currentQ.options && (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                {currentQ.options.map((opt, oi) => {
                  const selected = answers[currentQ.id] === opt
                  return (
                    <label key={oi} onClick={() => selectAnswer(opt)} style={{
                      display: 'flex', gap: 14, alignItems: 'flex-start',
                      padding: '14px 18px', borderRadius: 8, cursor: frozen ? 'not-allowed' : 'pointer',
                      border: `1.5px solid ${selected ? 'var(--color-primary-500)' : 'var(--color-surface-700)'}`,
                      background: selected ? 'rgba(240,78,56,0.05)' : '#ffffff',
                      transition: 'all 0.15s',
                    }}>
                      <div style={{
                        width: 20, height: 20, borderRadius: '50%', flexShrink: 0, marginTop: 2,
                        border: `2px solid ${selected ? 'var(--color-primary-500)' : 'var(--color-surface-600)'}`,
                        background: selected ? 'var(--color-primary-500)' : 'transparent',
                        display: 'flex', alignItems: 'center', justifyContent: 'center',
                      }}>
                        {selected && <div style={{ width: 8, height: 8, borderRadius: '50%', background: '#fff' }} />}
                      </div>
                      <span style={{
                        fontSize: '0.95rem', color: selected ? 'var(--color-primary-700)' : 'var(--color-surface-200)',
                        fontWeight: selected ? 600 : 400, lineHeight: 1.5, flex: 1,
                      }}>
                        <span style={{ color: 'var(--color-surface-400)', marginRight: 8, fontWeight: 600 }}>
                          {String.fromCharCode(65 + oi)}.
                        </span>
                        {opt}
                      </span>
                    </label>
                  )
                })}
              </div>
            )}

            {/* Essay */}
            {currentQ.type === 'ESSAY' && (
              <textarea
                className="input-field"
                rows={8}
                placeholder="Nhập câu trả lời của bạn..."
                disabled={frozen}
                style={{ resize: 'vertical', fontSize: '1rem', padding: 16, lineHeight: 1.7 }}
                value={answers[currentQ.id] || ''}
                onChange={e => setAnswers(prev => ({ ...prev, [currentQ.id]: e.target.value }))}
              />
            )}
          </div>

          {/* Prev / Next navigation */}
          <div style={{ display: 'flex', justifyContent: 'space-between', marginTop: 20 }}>
            <button className="btn-secondary" onClick={() => setCurrentIdx(i => i - 1)}
              disabled={currentIdx === 0} style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
              <ChevronLeft size={16} /> Câu trước
            </button>
            <button className="btn-secondary" onClick={() => setCurrentIdx(i => i + 1)}
              disabled={currentIdx === total - 1} style={{ display: 'flex', gap: 6, alignItems: 'center' }}>
              Câu sau <ChevronRight size={16} />
            </button>
          </div>
        </main>

        {/* ── Question Palette Sidebar ───────────────────────────────────── */}
        <aside style={{
          width: 220, background: '#ffffff', borderLeft: '1px solid var(--color-surface-700)',
          padding: '24px 16px', display: 'flex', flexDirection: 'column', gap: 20, overflowY: 'auto',
          position: 'sticky', top: 63, height: 'calc(100dvh - 63px)',
        }}>
          <div>
            <div style={{ fontSize: '0.8rem', fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.06em', color: 'var(--color-surface-400)', marginBottom: 12 }}>
              Bảng câu hỏi
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 6 }}>
              {questions.map((q, i) => {
                const state = getQState(q.id)
                const isActive = i === currentIdx
                let bg = 'var(--color-surface-800)'
                let color = 'var(--color-surface-400)'
                let borderC = 'transparent'
                if (state === 'answered') { bg = 'rgba(16,185,129,0.15)'; color = '#059669' }
                if (state === 'flagged')  { bg = 'rgba(245,158,11,0.15)'; color = '#d97706' }
                if (isActive) { borderC = 'var(--color-primary-500)' }

                return (
                  <button key={q.id} onClick={() => setCurrentIdx(i)} style={{
                    width: '100%', aspectRatio: '1', borderRadius: 6, border: `2px solid ${borderC}`,
                    background: bg, color, fontWeight: 700, fontSize: '0.8rem', cursor: 'pointer',
                    transition: 'all 0.15s', display: 'flex', alignItems: 'center', justifyContent: 'center',
                    position: 'relative',
                  }}>
                    {i + 1}
                    {state === 'flagged' && (
                      <span style={{ position: 'absolute', top: -4, right: -4, fontSize: '0.6rem' }}>🚩</span>
                    )}
                  </button>
                )
              })}
            </div>
          </div>

          {/* Legend */}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
            {[
              { color: 'var(--color-surface-800)', label: 'Chưa làm', textColor: 'var(--color-surface-400)' },
              { color: 'rgba(16,185,129,0.15)', label: 'Đã trả lời', textColor: '#059669' },
              { color: 'rgba(245,158,11,0.15)', label: 'Cắm cờ 🚩', textColor: '#d97706' },
            ].map(({ color: bg, label, textColor }) => (
              <div key={label} style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <div style={{ width: 16, height: 16, borderRadius: 4, background: bg, flexShrink: 0 }} />
                <span style={{ fontSize: '0.75rem', color: textColor }}>{label}</span>
              </div>
            ))}
          </div>

          {/* Progress summary */}
          <div style={{ padding: '12px', borderRadius: 8, background: 'var(--color-surface-800)', border: '1px solid var(--color-surface-700)' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: 8 }}>
              <span style={{ fontSize: '0.75rem', color: 'var(--color-surface-400)' }}>Tiến độ</span>
              <span style={{ fontSize: '0.75rem', fontWeight: 700, color: 'var(--color-surface-50)' }}>{answeredCount}/{total}</span>
            </div>
            <div style={{ height: 6, borderRadius: 3, background: 'var(--color-surface-700)', overflow: 'hidden' }}>
              <div style={{ height: '100%', width: `${progress}%`, background: 'var(--color-primary-500)', borderRadius: 3, transition: 'width 0.3s' }} />
            </div>
          </div>
        </aside>
      </div>

      {/* ── Submit Modal ─────────────────────────────────────────────────── */}
      {showModal && (
        <SubmitModal
          unanswered={unansweredCount}
          flagged={flaggedCount}
          submitting={submitting}
          onConfirm={doSubmit}
          onCancel={() => setShowModal(false)}
        />
      )}

      {/* ── Frozen overlay (auto-submit) ──────────────────────────────── */}
      {frozen && !submitted && (
        <div style={{
          position: 'fixed', inset: 0, zIndex: 999,
          background: 'rgba(0,0,0,0.7)', backdropFilter: 'blur(6px)',
          display: 'flex', alignItems: 'center', justifyContent: 'center',
        }}>
          <div style={{ textAlign: 'center', color: 'white' }}>
            <div style={{ fontSize: '1.5rem', fontWeight: 700, marginBottom: 8 }}>⏰ Hết giờ!</div>
            <p style={{ opacity: 0.8 }}>Đang tự động nộp bài của bạn...</p>
          </div>
        </div>
      )}

      <style>{`
        @keyframes heartbeat {
          0%,100% { transform: scale(1); }
          50%      { transform: scale(1.04); }
        }
      `}</style>
    </div>
  )
}

import { useState } from 'react'
import { FileText, Plus, Trash2, GripVertical } from 'lucide-react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { examApi, type CreateExamPayload } from './examApi'
import QuestionSelectorModal from './QuestionSelectorModal'
import { type Question } from '../question-bank/questionApi'

interface CreateExamWizardProps {
  onClose: () => void;
}

export default function CreateExamWizard({ onClose }: CreateExamWizardProps) {
  const qc = useQueryClient()
  const [step, setStep] = useState(1)
  
  // Form State
  const [title, setTitle] = useState('')
  const [description, setDescription] = useState('')
  const [type, setType] = useState<'PRACTICE' | 'OFFICIAL'>('PRACTICE')
  const [durationMinutes, setDurationMinutes] = useState(45)
  const [passingScore, setPassingScore] = useState(5)
  const [shuffleQuestions, setShuffleQuestions] = useState(false)
  const [shuffleOptions, setShuffleOptions] = useState(false)
  
  // Proctoring State
  const [requireCam, setRequireCam] = useState(false)
  const [requireMic, setRequireMic] = useState(false)
  const [blockTabSwitch, setBlockTabSwitch] = useState(true)
  const [requireFullscreen, setRequireFullscreen] = useState(true)
  
  // Selected Questions
  const [questions, setQuestions] = useState<(Question & { weight: number })[]>([])
  const [showQuestionSelector, setShowQuestionSelector] = useState(false)

  const createMut = useMutation({
    mutationFn: examApi.createExam,
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: ['exams'] })
      alert('Tạo đề thi thành công!')
      onClose()
    },
    onError: () => alert('Lỗi khi tạo đề thi')
  })

  const handleNext = () => {
    if (step === 1) {
      if (!title.trim() || durationMinutes <= 0) return alert('Vui lòng nhập tên đề thi và thời gian hợp lệ')
      setStep(2)
    } else if (step === 2) {
      if (questions.length === 0) return alert('Vui lòng chọn ít nhất 1 câu hỏi')
      setStep(3)
    } else if (step === 3) {
      // Submit
      const payload: CreateExamPayload = {
        title,
        description,
        type,
        durationMinutes,
        passingScore,
        shuffleQuestions,
        shuffleOptions,
        requireCam,
        requireMic,
        blockTabSwitch,
        requireFullscreen,
        questions: questions.map(q => ({ questionId: q.id, weight: q.weight }))
      }
      createMut.mutate(payload)
    }
  }

  const handleAddQuestions = (newQuestions: Question[]) => {
    const withWeight = newQuestions.map(q => ({ ...q, weight: 1 }))
    setQuestions(prev => [...prev, ...withWeight])
  }

  const removeQuestion = (id: string) => {
    setQuestions(prev => prev.filter(q => q.id !== id))
  }

  const updateWeight = (id: string, weight: number) => {
    if (weight < 0) return
    setQuestions(prev => prev.map(q => q.id === id ? { ...q, weight } : q))
  }

  const totalScore = questions.reduce((sum, q) => sum + q.weight, 0)

  return (
    <div style={{ minHeight: '100dvh', background: '#F4F4F5', display: 'flex', flexDirection: 'column' }}>
      
      {/* Header */}
      <div style={{ height: 80, borderBottom: '1px solid var(--color-surface-700)', display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0 32px', background: '#fff', flexShrink: 0, boxShadow: '0 2px 8px rgba(0,0,0,0.06)', position: 'sticky', top: 0, zIndex: 10 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
          <button onClick={onClose} className="btn-ghost" style={{ padding: '6px 12px' }}>Hủy</button>
          <div style={{ width: 1, height: 20, background: 'var(--color-surface-700)' }} />
          <span style={{ fontSize: '1.1rem', fontWeight: 600, color: 'var(--color-surface-50)' }}>Tạo đề thi mới</span>
        </div>
        
        {/* Step Indicator — chuẩn: [circle]──line──[circle] */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 0 }}>
          {[
            { s: 1, label: 'Thông tin chung' },
            { s: 2, label: 'Thêm câu hỏi' },
            { s: 3, label: 'Cài đặt & Xuất bản' }
          ].map((item, idx) => (
            <div key={item.s} style={{ display: 'flex', alignItems: 'center' }}>
              {/* Step node */}
              <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 6 }}>
                <div style={{
                  width: 32, height: 32, borderRadius: '50%',
                  background: step > item.s ? 'var(--color-success)' : step === item.s ? 'var(--color-primary-500)' : 'var(--color-surface-700)',
                  color: 'white', display: 'flex', alignItems: 'center', justifyContent: 'center',
                  fontSize: '0.875rem', fontWeight: 700,
                  transition: 'background 0.3s ease',
                  boxShadow: step === item.s ? '0 0 0 4px var(--color-primary-100)' : 'none',
                }}>
                  {step > item.s ? '✓' : item.s}
                </div>
                <span style={{
                  fontSize: '0.78rem', fontWeight: step === item.s ? 700 : 400,
                  color: step === item.s ? 'var(--color-primary-600)' : step > item.s ? 'var(--color-success)' : 'var(--color-surface-400)',
                  whiteSpace: 'nowrap', transition: 'color 0.3s ease',
                }}>
                  {item.label}
                </span>
              </div>

              {/* Connector line (between steps only) */}
              {idx < 2 && (
                <div style={{
                  width: 80, height: 2, margin: '0 4px', marginBottom: 22,
                  background: step > item.s ? 'var(--color-success)' : 'var(--color-surface-700)',
                  transition: 'background 0.3s ease',
                  flexShrink: 0,
                }} />
              )}
            </div>
          ))}
        </div>

        
        <div style={{ width: 80 }} /> {/* Spacer */}
      </div>

      {/* Body */}
      <div style={{ flex: 1, overflowY: 'auto', padding: '40px 24px', background: '#F9F9F9' }}>
        <div className="clean-card" style={{ maxWidth: 800, margin: '0 auto', padding: 40, background: '#ffffff', minHeight: '60vh' }}>
          
          {step === 1 && (
            <div className="page-enter">
              <h2 style={{ fontSize: '1.5rem', fontWeight: 700, marginBottom: 32, color: 'var(--color-surface-50)' }}>Bước 1: Thông tin cơ bản</h2>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 24 }}>
                <div>
                  <label style={{ display: 'block', marginBottom: 8, fontSize: '0.9rem', fontWeight: 600, color: 'var(--color-surface-200)' }}>Tên đề thi *</label>
                  <input type="text" className="input-field" placeholder="VD: Đề thi giữa kỳ môn Toán" value={title} onChange={e => setTitle(e.target.value)} />
                </div>
                <div>
                  <label style={{ display: 'block', marginBottom: 8, fontSize: '0.9rem', fontWeight: 600, color: 'var(--color-surface-200)' }}>Mô tả</label>
                  <textarea className="input-field" placeholder="Mô tả ngắn gọn về đề thi..." rows={3} value={description} onChange={e => setDescription(e.target.value)} />
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 24 }}>
                  <div>
                    <label style={{ display: 'block', marginBottom: 8, fontSize: '0.9rem', fontWeight: 600, color: 'var(--color-surface-200)' }}>Loại đề</label>
                    <select className="input-field" value={type} onChange={e => setType(e.target.value as any)}>
                      <option value="PRACTICE">Luyện tập</option>
                      <option value="OFFICIAL">Thi chính thức</option>
                    </select>
                  </div>
                  <div>
                    <label style={{ display: 'block', marginBottom: 8, fontSize: '0.9rem', fontWeight: 600, color: 'var(--color-surface-200)' }}>Thời gian (phút) *</label>
                    <input type="number" className="input-field" placeholder="45" value={durationMinutes} onChange={e => setDurationMinutes(Number(e.target.value))} min={1} />
                  </div>
                </div>
              </div>
            </div>
          )}

          {step === 2 && (
            <div className="page-enter" style={{ display: 'flex', flexDirection: 'column', height: '100%' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 24 }}>
                <h2 style={{ fontSize: '1.5rem', fontWeight: 700, color: 'var(--color-surface-50)', margin: 0 }}>Bước 2: Chọn câu hỏi</h2>
                <button className="btn-secondary" onClick={() => setShowQuestionSelector(true)}>
                  <Plus size={16} style={{ marginRight: 8 }} /> Chọn từ Ngân hàng
                </button>
              </div>

              {questions.length === 0 ? (
                <div style={{ padding: 60, textAlign: 'center', border: '2px dashed var(--color-surface-700)', borderRadius: 12, background: 'var(--color-surface-900)' }}>
                  <FileText size={48} color="var(--color-surface-600)" style={{ marginBottom: 16 }} />
                  <p style={{ color: 'var(--color-surface-400)', margin: '0 0 16px 0' }}>Chưa có câu hỏi nào trong đề.</p>
                  <button className="btn-primary" onClick={() => setShowQuestionSelector(true)}>Mở Ngân hàng câu hỏi</button>
                </div>
              ) : (
                <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', padding: '12px 16px', background: 'var(--color-primary-50)', borderRadius: 8, border: '1px solid var(--color-primary-200)' }}>
                    <span style={{ fontWeight: 600, color: 'var(--color-primary-800)' }}>Tổng cộng: {questions.length} câu</span>
                    <span style={{ fontWeight: 600, color: 'var(--color-primary-800)' }}>Tổng điểm: {totalScore}</span>
                  </div>
                  
                  {questions.map((q, idx) => (
                    <div key={q.id} style={{ display: 'flex', alignItems: 'flex-start', gap: 16, padding: '16px', border: '1px solid var(--color-surface-700)', borderRadius: 8, background: '#fff' }}>
                      <GripVertical size={20} color="var(--color-surface-600)" style={{ cursor: 'grab', marginTop: 2 }} />
                      <div style={{ fontWeight: 600, color: 'var(--color-surface-400)', width: 24, marginTop: 2 }}>{idx + 1}.</div>
                      <div style={{ flex: 1 }}>
                        <div style={{ fontSize: '0.95rem', color: 'var(--color-surface-50)', lineHeight: 1.5, marginBottom: 8, display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>
                          {q.content}
                        </div>
                        <div style={{ display: 'flex', gap: 8 }}>
                          <span className="badge badge-gray">{q.questionType}</span>
                          <span className="badge badge-gray">{q.difficultyLevel}</span>
                        </div>
                      </div>
                      <div style={{ display: 'flex', flexDirection: 'column', gap: 8, alignItems: 'flex-end', width: 100 }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                          <label style={{ fontSize: '0.8rem', color: 'var(--color-surface-400)' }}>Điểm:</label>
                          <input type="number" value={q.weight} onChange={e => updateWeight(q.id, Number(e.target.value))} className="input-field" style={{ width: 60, padding: '4px 8px', textAlign: 'center' }} min={0} step={0.5} />
                        </div>
                        <button onClick={() => removeQuestion(q.id)} className="btn-ghost" style={{ color: 'var(--color-error)', padding: 4 }}>
                          <Trash2 size={16} /> Xóa
                        </button>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}

          {step === 3 && (
            <div className="page-enter">
              <h2 style={{ fontSize: '1.5rem', fontWeight: 700, marginBottom: 32, color: 'var(--color-surface-50)' }}>Bước 3: Cài đặt & Xuất bản</h2>
              
              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 24, marginBottom: 32 }}>
                <div style={{ padding: 20, background: 'var(--color-surface-900)', borderRadius: 8, border: '1px solid var(--color-surface-700)' }}>
                  <div style={{ fontSize: '0.85rem', color: 'var(--color-surface-400)', textTransform: 'uppercase', marginBottom: 4 }}>Tổng số câu hỏi</div>
                  <div style={{ fontSize: '1.5rem', fontWeight: 700, color: 'var(--color-surface-50)' }}>{questions.length}</div>
                </div>
                <div style={{ padding: 20, background: 'var(--color-surface-900)', borderRadius: 8, border: '1px solid var(--color-surface-700)' }}>
                  <div style={{ fontSize: '0.85rem', color: 'var(--color-surface-400)', textTransform: 'uppercase', marginBottom: 4 }}>Tổng điểm</div>
                  <div style={{ fontSize: '1.5rem', fontWeight: 700, color: 'var(--color-surface-50)' }}>{totalScore}</div>
                </div>
              </div>

              <div style={{ marginBottom: 32 }}>
                <label style={{ display: 'block', marginBottom: 8, fontSize: '0.9rem', fontWeight: 600, color: 'var(--color-surface-200)' }}>Điểm đạt yêu cầu (Passing Score)</label>
                <input type="number" className="input-field" style={{ maxWidth: 200 }} value={passingScore} onChange={e => setPassingScore(Number(e.target.value))} min={0} max={totalScore} step={0.5} />
                {passingScore > totalScore && <div style={{ color: 'var(--color-error)', fontSize: '0.8rem', marginTop: 4 }}>Điểm đạt yêu cầu không được lớn hơn tổng điểm ({totalScore})</div>}
              </div>

              <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 24 }}>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
                  <h3 style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--color-surface-50)', margin: '0 0 8px 0' }}>Hiển thị đề thi</h3>
                  
                  <label style={{ display: 'flex', alignItems: 'center', gap: 16, padding: 16, border: `1px solid ${shuffleQuestions ? 'var(--color-primary-400)' : 'var(--color-surface-700)'}`, background: shuffleQuestions ? 'var(--color-primary-50)' : '#fff', borderRadius: 8, cursor: 'pointer', transition: 'all 0.2s' }}>
                    <input type="checkbox" checked={shuffleQuestions} onChange={e => setShuffleQuestions(e.target.checked)} style={{ width: 20, height: 20, accentColor: 'var(--color-primary-500)' }} />
                    <div>
                      <div style={{ fontWeight: 600, color: 'var(--color-surface-50)', marginBottom: 2 }}>Đảo vị trí câu hỏi</div>
                      <div style={{ fontSize: '0.8rem', color: 'var(--color-surface-400)' }}>Thứ tự hiển thị ngẫu nhiên</div>
                    </div>
                  </label>
                  
                  <label style={{ display: 'flex', alignItems: 'center', gap: 16, padding: 16, border: `1px solid ${shuffleOptions ? 'var(--color-primary-400)' : 'var(--color-surface-700)'}`, background: shuffleOptions ? 'var(--color-primary-50)' : '#fff', borderRadius: 8, cursor: 'pointer', transition: 'all 0.2s' }}>
                    <input type="checkbox" checked={shuffleOptions} onChange={e => setShuffleOptions(e.target.checked)} style={{ width: 20, height: 20, accentColor: 'var(--color-primary-500)' }} />
                    <div>
                      <div style={{ fontWeight: 600, color: 'var(--color-surface-50)', marginBottom: 2 }}>Đảo thứ tự đáp án</div>
                      <div style={{ fontSize: '0.8rem', color: 'var(--color-surface-400)' }}>Thứ tự A,B,C,D hiển thị ngẫu nhiên</div>
                    </div>
                  </label>
                </div>

                <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
                  <h3 style={{ fontSize: '1rem', fontWeight: 600, color: 'var(--color-surface-50)', margin: '0 0 8px 0' }}>Giám sát thi (Proctoring)</h3>
                  
                  <label style={{ display: 'flex', alignItems: 'center', gap: 16, padding: 16, border: `1px solid ${blockTabSwitch ? 'var(--color-primary-400)' : 'var(--color-surface-700)'}`, background: blockTabSwitch ? 'var(--color-primary-50)' : '#fff', borderRadius: 8, cursor: 'pointer', transition: 'all 0.2s' }}>
                    <input type="checkbox" checked={blockTabSwitch} onChange={e => setBlockTabSwitch(e.target.checked)} style={{ width: 20, height: 20, accentColor: 'var(--color-primary-500)' }} />
                    <div>
                      <div style={{ fontWeight: 600, color: 'var(--color-surface-50)', marginBottom: 2 }}>Chặn chuyển Tab</div>
                      <div style={{ fontSize: '0.8rem', color: 'var(--color-surface-400)' }}>Cảnh báo & tự nộp bài nếu rời tab</div>
                    </div>
                  </label>

                  <label style={{ display: 'flex', alignItems: 'center', gap: 16, padding: 16, border: `1px solid ${requireFullscreen ? 'var(--color-primary-400)' : 'var(--color-surface-700)'}`, background: requireFullscreen ? 'var(--color-primary-50)' : '#fff', borderRadius: 8, cursor: 'pointer', transition: 'all 0.2s' }}>
                    <input type="checkbox" checked={requireFullscreen} onChange={e => setRequireFullscreen(e.target.checked)} style={{ width: 20, height: 20, accentColor: 'var(--color-primary-500)' }} />
                    <div>
                      <div style={{ fontWeight: 600, color: 'var(--color-surface-50)', marginBottom: 2 }}>Bắt buộc Toàn màn hình</div>
                      <div style={{ fontSize: '0.8rem', color: 'var(--color-surface-400)' }}>Phải bật Fullscreen mới làm bài được</div>
                    </div>
                  </label>
                  
                  <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 16 }}>
                    <label style={{ display: 'flex', alignItems: 'center', gap: 12, padding: 12, border: `1px solid ${requireCam ? 'var(--color-primary-400)' : 'var(--color-surface-700)'}`, background: requireCam ? 'var(--color-primary-50)' : '#fff', borderRadius: 8, cursor: 'pointer', transition: 'all 0.2s' }}>
                      <input type="checkbox" checked={requireCam} onChange={e => setRequireCam(e.target.checked)} style={{ width: 16, height: 16, accentColor: 'var(--color-primary-500)' }} />
                      <div style={{ fontWeight: 600, fontSize: '0.85rem', color: 'var(--color-surface-50)' }}>Yêu cầu Webcam</div>
                    </label>

                    <label style={{ display: 'flex', alignItems: 'center', gap: 12, padding: 12, border: `1px solid ${requireMic ? 'var(--color-primary-400)' : 'var(--color-surface-700)'}`, background: requireMic ? 'var(--color-primary-50)' : '#fff', borderRadius: 8, cursor: 'pointer', transition: 'all 0.2s' }}>
                      <input type="checkbox" checked={requireMic} onChange={e => setRequireMic(e.target.checked)} style={{ width: 16, height: 16, accentColor: 'var(--color-primary-500)' }} />
                      <div style={{ fontWeight: 600, fontSize: '0.85rem', color: 'var(--color-surface-50)' }}>Yêu cầu Micro</div>
                    </label>
                  </div>
                </div>
              </div>
            </div>
          )}
        </div>
      </div>

      {/* Footer */}
      <div style={{ height: 80, borderTop: '1px solid var(--color-surface-700)', display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0 40px', background: '#ffffff', boxShadow: '0 -4px 12px rgba(0,0,0,0.05)' }}>
        <button className="btn-ghost" onClick={() => step > 1 ? setStep(s => s - 1) : onClose()}>
          {step === 1 ? 'Hủy bỏ' : 'Quay lại bước trước'}
        </button>
        <button className="btn-primary" onClick={handleNext} disabled={createMut.isPending || (step === 3 && passingScore > totalScore)} style={{ height: 48, paddingInline: 32, fontSize: '1rem' }}>
          {createMut.isPending ? 'Đang lưu...' : step < 3 ? 'Tiếp tục' : <><Rocket size={18} style={{ marginRight: 8 }} /> Lưu & Hoàn tất</>}
        </button>
      </div>

      {showQuestionSelector && (
        <QuestionSelectorModal 
          alreadySelectedIds={questions.map(q => q.id)}
          onSelect={handleAddQuestions}
          onClose={() => setShowQuestionSelector(false)}
        />
      )}
    </div>
  )
}

import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { X, Plus, Trash2 } from 'lucide-react'
import { apiError, questionBankApi, type Category, type Question, type QuestionInput, type QuestionType, type SourceType } from './questionApi'

export default function QuestionModal({ categories, initialData, onClose }: {
  categories: Category[]; initialData?: Question | null; onClose: () => void
}) {
  const qc = useQueryClient()
  const [categoryId, setCategoryId] = useState(initialData?.categoryId ?? '')
  const [content, setContent] = useState(initialData?.content ?? '')
  const [type, setType] = useState<QuestionType>(initialData?.questionType ?? 'MULTIPLE_CHOICE')
  const [difficulty, setDifficulty] = useState<Question['difficultyLevel']>(initialData?.difficultyLevel ?? 'MEDIUM')
  const [options, setOptions] = useState(initialData?.options.map(o => ({ content: o.content, correct: o.correct ?? false }))
    ?? [{ content: '', correct: true }, { content: '', correct: false }])
  const [expectedAnswer, setExpectedAnswer] = useState(initialData?.expectedAnswer ?? '')
  const [sourceType, setSourceType] = useState<SourceType>(initialData?.sourceType ?? 'NONE')
  const [sourceLabel, setSourceLabel] = useState(initialData?.sourceLabel ?? initialData?.source ?? '')
  const [sourceUrl, setSourceUrl] = useState(initialData?.sourceUrl ?? '')
  const [sourceRefId, setSourceRefId] = useState(initialData?.sourceRefId ?? '')
  const [sourceExcerpt, setSourceExcerpt] = useState(initialData?.sourceExcerpt ?? '')
  const [error, setError] = useState('')
  const { data: editCategories } = useQuery({
    queryKey: ['edit-category', initialData?.categoryId],
    queryFn: () => questionBankApi.getCategory(initialData!.categoryId),
    enabled: !!initialData && !categories.some(c => c.id === initialData.categoryId),
  })
  const available = editCategories && !categories.some(c => c.id === editCategories.id) ? [editCategories, ...categories] : categories
  const save = useMutation({
    mutationFn: (data: QuestionInput) => initialData ? questionBankApi.updateQuestion(initialData.id, data) : questionBankApi.createQuestion(data),
    onSuccess: async () => { await qc.invalidateQueries({ queryKey: ['questions'] }); await qc.invalidateQueries({ queryKey: ['question'] }); await qc.invalidateQueries({ queryKey: ['versions'] }); onClose() },
    onError: e => setError(apiError(e)),
  })
  const choice = type === 'MULTIPLE_CHOICE' || type === 'TRUE_FALSE'
  function changeType(next: QuestionType) {
    setType(next); setExpectedAnswer('')
    setOptions(next === 'TRUE_FALSE' ? [{ content: 'Đúng', correct: true }, { content: 'Sai', correct: false }]
      : next === 'MULTIPLE_CHOICE' ? [{ content: '', correct: true }, { content: '', correct: false }] : [])
  }
  function submit(e: FormEvent) {
    e.preventDefault(); setError('')
    if (choice && (options.length < 2 || options.some(o => !o.content.trim()) || options.filter(o => o.correct).length !== 1)) {
      setError('Nhập đủ lựa chọn và chọn đúng một đáp án.'); return
    }
    if (type === 'SHORT_ANSWER' && !expectedAnswer.trim()) { setError('Cần đáp án mong đợi hoặc hướng dẫn chấm.'); return }
    save.mutate({ categoryId, content: content.trim(), questionType: type, difficultyLevel: difficulty,
      options: choice ? options.map((o, i) => ({ ...o, content: o.content.trim(), displayOrder: i })) : [],
      expectedAnswer: choice ? '' : expectedAnswer.trim(), sourceType, sourceLabel, sourceRefId, sourceUrl, sourceExcerpt,
      questionVersion: initialData?.questionVersion })
  }
  return <div style={{ position: 'fixed', inset: 0, zIndex: 1000, background: 'rgba(0,0,0,.5)', display: 'grid', placeItems: 'center', padding: 20 }}>
    <section role="dialog" aria-modal="true" aria-labelledby="question-form-title" className="clean-card" style={{ width: 'min(720px,100%)', maxHeight: '90vh', overflowY: 'auto', padding: 24 }}>
      <header style={{ display: 'flex', justifyContent: 'space-between' }}>
        <h2 id="question-form-title">{initialData ? 'Sửa câu hỏi' : 'Tạo câu hỏi DRAFT'}</h2>
        <button className="btn-ghost" aria-label="Đóng" onClick={onClose} disabled={save.isPending}><X size={20}/></button>
      </header>
      {initialData?.status === 'APPROVED' && <p>Chỉnh sửa tạo phiên bản mới ở DRAFT và cần duyệt lại. Phiên bản cũ được giữ trong lịch sử.</p>}
      <form onSubmit={submit} style={{ display: 'grid', gap: 16 }}>
        <label>Danh mục<select className="input-field" required value={categoryId} onChange={e => setCategoryId(e.target.value)}>
          <option value="">Chọn danh mục</option>{available.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}
        </select></label>
        <label>Loại câu hỏi<select className="input-field" value={type} onChange={e => changeType(e.target.value as QuestionType)}>
          <option value="MULTIPLE_CHOICE">Trắc nghiệm</option><option value="TRUE_FALSE">Đúng/Sai</option><option value="SHORT_ANSWER">Trả lời ngắn</option><option value="ESSAY">Tự luận</option>
        </select></label>
        <label>Độ khó<select className="input-field" value={difficulty} onChange={e => setDifficulty(e.target.value as Question['difficultyLevel'])}>
          <option value="EASY">Dễ</option><option value="MEDIUM">Trung bình</option><option value="HARD">Khó</option>
        </select></label>
        <label>Nội dung<textarea className="input-field" rows={4} required maxLength={5000} value={content} onChange={e => setContent(e.target.value)}/></label>
        {choice ? <fieldset><legend>Lựa chọn — chọn một đáp án đúng</legend>
          {options.map((o, i) => <div key={i} style={{ display: 'flex', gap: 8, marginBottom: 8 }}>
            <input type="radio" name="correct" aria-label={`Đáp án ${i + 1} đúng`} checked={o.correct} onChange={() => setOptions(options.map((v, n) => ({ ...v, correct: i === n })))}/>
            <input className="input-field" aria-label={`Lựa chọn ${i + 1}`} required maxLength={5000} value={o.content} onChange={e => setOptions(options.map((v, n) => n === i ? { ...v, content: e.target.value } : v))}/>
            {type === 'MULTIPLE_CHOICE' && options.length > 2 && <button type="button" className="btn-ghost" aria-label={`Xóa lựa chọn ${i + 1}`} onClick={() => setOptions(options.filter((_, n) => n !== i))}><Trash2 size={16}/></button>}
          </div>)}
          {type === 'MULTIPLE_CHOICE' && options.length < 26 && <button type="button" className="btn-secondary" onClick={() => setOptions([...options, { content: '', correct: false }])}><Plus size={16}/> Thêm lựa chọn</button>}
        </fieldset> : <label>Đáp án mong đợi / hướng dẫn chấm {type === 'ESSAY' && '(tùy chọn)'}<textarea className="input-field" required={type === 'SHORT_ANSWER'} rows={3} maxLength={10000} value={expectedAnswer} onChange={e => setExpectedAnswer(e.target.value)}/></label>}
        <fieldset style={{ display: 'grid', gap: 12 }}><legend>Nguồn nội dung (thông tin tham chiếu)</legend>
          <label>Loại nguồn<select className="input-field" value={sourceType} onChange={e => setSourceType(e.target.value as SourceType)}>
            {(['NONE','USER_DOCUMENT','EXTERNAL_WEB','ORGANIZATION_BANK','PUBLIC_BANK','SYSTEM_BANK'] as SourceType[]).map(s => <option key={s}>{s}</option>)}
          </select></label>
          <label>Nhãn nguồn<input className="input-field" maxLength={500} value={sourceLabel} onChange={e => setSourceLabel(e.target.value)}/></label>
          <label>Mã tham chiếu<input className="input-field" maxLength={255} value={sourceRefId} onChange={e => setSourceRefId(e.target.value)}/></label>
          <label>URL nguồn<input className="input-field" type="url" maxLength={2000} value={sourceUrl} onChange={e => setSourceUrl(e.target.value)}/></label>
          <label>Trích đoạn<textarea className="input-field" rows={2} maxLength={10000} value={sourceExcerpt} onChange={e => setSourceExcerpt(e.target.value)}/></label>
        </fieldset>
        {error && <p role="alert" style={{ color: 'var(--color-error)' }}>{error}</p>}
        <footer style={{ display: 'flex', justifyContent: 'flex-end', gap: 12 }}>
          <button type="button" className="btn-secondary" disabled={save.isPending} onClick={onClose}>Hủy</button>
          <button className="btn-primary" disabled={save.isPending}>{save.isPending ? 'Đang lưu…' : 'Lưu DRAFT'}</button>
        </footer>
      </form>
    </section>
  </div>
}

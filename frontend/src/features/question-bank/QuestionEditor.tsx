import { useState, type FormEvent } from 'react'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Plus, Trash2 } from 'lucide-react'
import { questionBankApi, type Question, type QuestionInput, type QuestionType, type SourceType } from './questionApi'
import { Dialog } from '../../components/Dialog'
import { Field, Notice } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { DIFFICULTY_LABEL } from '../../lib/format'
import SubjectCategoryFields from './SubjectCategoryFields'
import { t as tt, useT, i18nMap } from '../../lib/i18n'
import Select from '../../components/Select'

const TYPES: { value: QuestionType; label: string; hint: string }[] = i18nMap([
  { value: 'MULTIPLE_CHOICE', label: 'Trắc nghiệm', hint: '2 đến 26 lựa chọn, đúng một đáp án. Chấm tự động.' },
  { value: 'TRUE_FALSE', label: 'Đúng/Sai', hint: 'Chọn mệnh đề đúng hay sai. Chấm tự động.' },
  { value: 'SHORT_ANSWER', label: 'Trả lời ngắn', hint: 'So khớp với đáp án chuẩn. Chấm tự động.' },
  { value: 'ESSAY', label: 'Tự luận', hint: 'Giáo viên chấm tay sau khi thí sinh nộp bài.' },
])
// Phase 1 provenance only; bank-level sources belong to later phases.
const SOURCES: { value: SourceType; label: string }[] = i18nMap([
  { value: 'NONE', label: 'Không ghi nguồn' }, { value: 'USER_DOCUMENT', label: 'Tài liệu của tôi' }, { value: 'EXTERNAL_WEB', label: 'Trang web bên ngoài' },
])
const letter = (i: number) => String.fromCharCode(65 + i)

function initialTrueIsCorrect(q?: Question | null) {
  if (!q || q.questionType !== 'TRUE_FALSE') return true
  const correct = q.options.find(o => o.correct)
  if (!correct) return true
  const text = correct.content.trim().toLowerCase()
  return text === 'đúng' ? true : text === 'sai' ? false : q.options.indexOf(correct) === 0
}

export default function QuestionEditor({ initial, onClose, onSaved }: { initial?: Question | null; onClose: () => void; onSaved: (q: Question) => void }) {
  useT()
  const qc = useQueryClient()
  const [subjectId, setSubjectId] = useState('')
  const [categoryId, setCategoryId] = useState(initial?.categoryId ?? '')
  const [type, setType] = useState<QuestionType>(initial?.questionType ?? 'MULTIPLE_CHOICE')
  const [difficulty, setDifficulty] = useState<Question['difficultyLevel']>(initial?.difficultyLevel ?? 'MEDIUM')
  const [content, setContent] = useState(initial?.content ?? '')
  const [options, setOptions] = useState(initial?.questionType === 'MULTIPLE_CHOICE'
    ? initial.options.map(o => ({ content: o.content, correct: !!o.correct }))
    : [{ content: '', correct: true }, { content: '', correct: false }, { content: '', correct: false }, { content: '', correct: false }])
  const [trueCorrect, setTrueCorrect] = useState(initialTrueIsCorrect(initial))
  const [expectedAnswer, setExpectedAnswer] = useState(initial?.expectedAnswer ?? '')
  const [explanation, setExplanation] = useState(initial?.explanation ?? '')
  const [sourceType, setSourceType] = useState<SourceType>(initial?.sourceType ?? 'NONE')
  const [sourceLabel, setSourceLabel] = useState(initial?.sourceLabel ?? initial?.source ?? '')
  const [sourceUrl, setSourceUrl] = useState(initial?.sourceUrl ?? '')
  const [sourceRefId, setSourceRefId] = useState(initial?.sourceRefId ?? '')
  const [sourceExcerpt, setSourceExcerpt] = useState(initial?.sourceExcerpt ?? '')
  const [error, setError] = useState('')

  const save = useMutation({
    mutationFn: (data: QuestionInput) => initial ? questionBankApi.updateQuestion(initial.id, data) : questionBankApi.createQuestion(data),
    onSuccess: async q => {
      await qc.invalidateQueries({ queryKey: ['questions'] })
      await qc.invalidateQueries({ queryKey: ['question'] })
      await qc.invalidateQueries({ queryKey: ['versions'] })
      onSaved(q)
    },
    onError: e => setError(errorText(e)),
  })

  function submit(e: FormEvent) {
    e.preventDefault(); setError('')
    if (!categoryId) return setError(tt('Chọn môn học và danh mục.'))
    if (!content.trim()) return setError(tt('Nhập nội dung câu hỏi.'))
    let opts: QuestionInput['options'] = []
    if (type === 'MULTIPLE_CHOICE') {
      if (options.length < 2 || options.some(o => !o.content.trim())) return setError(tt('Nhập nội dung cho tất cả lựa chọn (ít nhất 2).'))
      if (options.filter(o => o.correct).length !== 1) return setError(tt('Chọn đúng một đáp án đúng.'))
      opts = options.map((o, i) => ({ content: o.content.trim(), correct: o.correct, displayOrder: i }))
    } else if (type === 'TRUE_FALSE') {
      opts = [{ content: tt('Đúng'), correct: trueCorrect, displayOrder: 0 }, { content: 'Sai', correct: !trueCorrect, displayOrder: 1 }]
    } else if (type === 'SHORT_ANSWER' && !expectedAnswer.trim()) {
      return setError(tt('Câu trả lời ngắn cần đáp án chuẩn.'))
    }
    save.mutate({
      categoryId, content: content.trim(), questionType: type, difficultyLevel: difficulty, options: opts,
      expectedAnswer: type === 'SHORT_ANSWER' || type === 'ESSAY' ? expectedAnswer.trim() : '',
      explanation,
      sourceType, sourceLabel: sourceType === 'NONE' ? '' : sourceLabel, sourceRefId: sourceType === 'NONE' ? '' : sourceRefId,
      sourceUrl: sourceType === 'NONE' ? '' : sourceUrl, sourceExcerpt: sourceType === 'NONE' ? '' : sourceExcerpt,
      questionVersion: initial?.questionVersion,
    })
  }

  const sources = SOURCES.some(s => s.value === sourceType) ? SOURCES : [...SOURCES, { value: sourceType, label: sourceType }]

  return (
    <Dialog size="lg" title={initial ? tt('Sửa câu hỏi (phiên bản {0})', { 0: initial.questionVersion }) : tt('Tạo câu hỏi mới')} onClose={onClose} busy={save.isPending}
      description={tt('Câu hỏi được lưu ở trạng thái Bản nháp. Kích hoạt khi đã hoàn chỉnh để dùng; không cần gửi duyệt.')}
      footer={<>
        <button type="button" className="btn btn-secondary" onClick={onClose} disabled={save.isPending}>{tt('Hủy')}</button>
        <button type="submit" form="question-form" className="btn btn-primary" disabled={save.isPending}>{save.isPending ? tt('Đang lưu…') : tt('Lưu bản nháp')}</button>
      </>}>
      <form id="question-form" onSubmit={submit} className="stack-lg">
        {initial?.status === 'ACTIVE' && <Notice tone="warning">{tt('Câu hỏi đang dùng. Lưu thay đổi tạo phiên bản mới ở trạng thái Bản nháp; cần kích hoạt lại. Đề thi đã xuất bản vẫn giữ phiên bản cũ.')}</Notice>}
        <div className="grid-2">
          <SubjectCategoryFields subjectId={subjectId} categoryId={categoryId} onSubject={setSubjectId} onCategory={setCategoryId} required />
        </div>
        <fieldset className="stack-sm">
          <legend className="field-label" style={{ marginBottom: 6 }}>{tt('Loại câu hỏi')}</legend>
          <div className="grid-2" style={{ gap: 8 }}>
            {TYPES.map(t => (
              <label key={t.value} className="choice">
                <input type="radio" name="question-type" value={t.value} checked={type === t.value} onChange={() => { setType(t.value); setError('') }} />
                <span><strong style={{ color: 'var(--color-ink)' }}>{t.label}</strong><br /><span className="xsmall muted">{t.hint}</span></span>
              </label>
            ))}
          </div>
        </fieldset>
        <Field label={tt('Độ khó')} htmlFor="q-difficulty">
          <Select id="q-difficulty" className="input" style={{ maxWidth: 240 }} value={difficulty} onChange={e => setDifficulty(e.target.value as Question['difficultyLevel'])}>
            {Object.entries(DIFFICULTY_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
          </Select>
        </Field>
        <Field label={tt('Nội dung câu hỏi')} htmlFor="q-content" required hint={tt('{0}/5000 ký tự', { 0: content.length })}>
          <textarea id="q-content" className="input" rows={4} maxLength={5000} required value={content} onChange={e => setContent(e.target.value)} />
        </Field>

        {type === 'MULTIPLE_CHOICE' && (
          <fieldset className="stack-sm">
            <legend className="field-label" style={{ marginBottom: 4 }}>{tt('Lựa chọn và đáp án đúng')}</legend>
            <p className="field-hint" style={{ marginBottom: 4 }}>{tt('Chọn nút tròn ở lựa chọn đúng. Thí sinh không bao giờ thấy đáp án trước khi chấm.')}</p>
            {options.map((o, i) => (
              <div key={i} className="row" style={{ flexWrap: 'nowrap', alignItems: 'center' }}>
                <label className="check" style={{ alignItems: 'center' }} title={tt('Đáp án đúng')}>
                  <input type="radio" name="mc-correct" checked={o.correct} aria-label={tt('Lựa chọn {0} là đáp án đúng', { 0: letter(i) })}
                    onChange={() => setOptions(options.map((v, n) => ({ ...v, correct: n === i })))} />
                  <span className="option-key" aria-hidden>{letter(i)}</span>
                </label>
                <input className="input" aria-label={tt('Nội dung lựa chọn {0}', { 0: letter(i) })} maxLength={5000} value={o.content}
                  onChange={e => setOptions(options.map((v, n) => n === i ? { ...v, content: e.target.value } : v))} />
                <button type="button" className="btn btn-ghost btn-icon" aria-label={tt('Xóa lựa chọn {0}', { 0: letter(i) })} disabled={options.length <= 2}
                  onClick={() => { const next = options.filter((_, n) => n !== i); if (!next.some(v => v.correct)) next[0] = { ...next[0], correct: true }; setOptions(next) }}>
                  <Trash2 size={16} />
                </button>
              </div>
            ))}
            {options.length < 26 && (
              <div><button type="button" className="btn btn-secondary btn-sm" onClick={() => setOptions([...options, { content: '', correct: false }])}>
                <Plus size={14} aria-hidden /> {tt('Thêm lựa chọn')}
              </button></div>
            )}
          </fieldset>
        )}

        {type === 'TRUE_FALSE' && (
          <fieldset className="stack-sm">
            <legend className="field-label" style={{ marginBottom: 6 }}>{tt('Mệnh đề trên là')}</legend>
            <div className="row">
              <label className="choice" style={{ minWidth: 140 }}><input type="radio" name="tf-answer" checked={trueCorrect} onChange={() => setTrueCorrect(true)} /> {tt('Đúng')}</label>
              <label className="choice" style={{ minWidth: 140 }}><input type="radio" name="tf-answer" checked={!trueCorrect} onChange={() => setTrueCorrect(false)} /> Sai</label>
            </div>
            <p className="field-hint">{tt('Thí sinh sẽ thấy hai lựa chọn "Đúng" và "Sai".')}</p>
          </fieldset>
        )}

        {type === 'SHORT_ANSWER' && (
          <Field label={tt('Đáp án chuẩn')} htmlFor="q-expected" required
            hint={tt('Bài làm được chấm đúng khi khớp chính xác với đáp án này (phân biệt chữ hoa/thường; khoảng trắng thừa được chuẩn hóa).')}>
            <input id="q-expected" className="input" maxLength={10000} required value={expectedAnswer} onChange={e => setExpectedAnswer(e.target.value)} />
          </Field>
        )}

        {type === 'ESSAY' && (
          <Field label={tt('Hướng dẫn chấm (tùy chọn)')} htmlFor="q-rubric"
            hint={tt('Chỉ người soạn và người chấm thấy. Bài có câu tự luận sẽ ở trạng thái Cần chấm tay cho đến khi giáo viên chấm.')}>
            <textarea id="q-rubric" className="input" rows={3} maxLength={10000} value={expectedAnswer} onChange={e => setExpectedAnswer(e.target.value)} />
          </Field>
        )}

        <Field label={tt('Giải thích (tùy chọn)')} htmlFor="q-explanation" hint={tt('Tối đa 10.000 ký tự. Lưu cùng phiên bản câu hỏi; chỉ xuất hiện trong kết quả đã công bố khi chính sách cho phép và kỳ thi đã đóng.')}>
          <textarea id="q-explanation" className="input" rows={3} maxLength={10000} value={explanation} onChange={e => setExplanation(e.target.value)} />
        </Field>

        <details>
          <summary className="field-label" style={{ cursor: 'pointer' }}>{tt('Nguồn tham khảo (tùy chọn)')}</summary>
          <div className="stack" style={{ marginTop: 12 }}>
            <Field label={tt('Loại nguồn')} htmlFor="q-source-type">
              <Select id="q-source-type" className="input" style={{ maxWidth: 280 }} value={sourceType} onChange={e => setSourceType(e.target.value as SourceType)}>
                {sources.map(s => <option key={s.value} value={s.value}>{s.label}</option>)}
              </Select>
            </Field>
            {sourceType !== 'NONE' && <>
              <div className="grid-2">
                <Field label={tt('Tên nguồn')} htmlFor="q-source-label"><input id="q-source-label" className="input" maxLength={500} value={sourceLabel} onChange={e => setSourceLabel(e.target.value)} /></Field>
                <Field label={tt('Mã tham chiếu')} htmlFor="q-source-ref"><input id="q-source-ref" className="input" maxLength={255} value={sourceRefId} onChange={e => setSourceRefId(e.target.value)} /></Field>
              </div>
              <Field label="URL" htmlFor="q-source-url"><input id="q-source-url" type="url" className="input" maxLength={2000} value={sourceUrl} onChange={e => setSourceUrl(e.target.value)} /></Field>
              <Field label={tt('Trích đoạn')} htmlFor="q-source-excerpt"><textarea id="q-source-excerpt" className="input" rows={2} maxLength={10000} value={sourceExcerpt} onChange={e => setSourceExcerpt(e.target.value)} /></Field>
            </>}
          </div>
        </details>
        {error && <Notice tone="danger" live>{error}</Notice>}
      </form>
    </Dialog>
  )
}

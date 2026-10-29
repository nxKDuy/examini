import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { importApi, type Difficulty } from './importApi'
import { PROVIDER_LABEL, aiProblems } from './importModel'
import type { QuestionType } from '../question-bank/questionApi'
import SubjectCategoryFields from '../question-bank/SubjectCategoryFields'
import { Dialog } from '../../components/Dialog'
import { Field, Notice, Spinner } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { DIFFICULTY_LABEL, QUESTION_TYPE_LABEL } from '../../lib/format'
import { t, useT } from '../../lib/i18n'
import Select from '../../components/Select'

const TYPES: QuestionType[] = ['MULTIPLE_CHOICE', 'TRUE_FALSE', 'SHORT_ANSWER', 'ESSAY']

/**
 * AI generation step: topic (+ optional knowledge text) → a staged job reviewed on the same page as a
 * document import. Nothing reaches the bank until the teacher reviews and imports it as a draft.
 */
export default function AiGenerateDialog({ onClose }: { onClose: () => void }) {
  useT()
  const navigate = useNavigate()
  const status = useQuery({ queryKey: ['ai-status'], queryFn: importApi.aiStatus, staleTime: 60_000 })
  const max = status.data?.maxQuestions ?? 20, maxMaterial = status.data?.maxMaterialChars ?? 20000
  const [topic, setTopic] = useState('')
  const [material, setMaterial] = useState('')
  const [subjectId, setSubjectId] = useState('')
  const [categoryId, setCategoryId] = useState('')
  const [count, setCount] = useState(5)
  const [types, setTypes] = useState<QuestionType[]>(['MULTIPLE_CHOICE'])
  const [difficulty, setDifficulty] = useState<Difficulty>('MEDIUM')
  const [language, setLanguage] = useState<'vi' | 'en'>('vi')
  const [useBank, setUseBank] = useState(true)
  const [useWeb, setUseWeb] = useState(true)
  const [touched, setTouched] = useState(false)
  const problems = aiProblems({ topic, categoryId, count, questionTypes: types, material }, max, maxMaterial)
  const err = (k: keyof typeof problems) => (touched ? problems[k] : undefined)
  const generate = useMutation({
    mutationFn: () => importApi.generate({ topic: topic.trim(), material: material.trim() || undefined, categoryId, count, questionTypes: types,
      difficulty, language, useBankExamples: useBank, useWebKnowledge: useWeb && !material.trim() }),
    onSuccess: job => navigate(`/questions/imports/${job.id}`),
  })
  const unavailable = status.data && !status.data.available
  const toggle = (type: QuestionType, on: boolean) => setTypes(x => on ? TYPES.filter(v => v === type || x.includes(v)) : x.filter(v => v !== type))

  return (
    <Dialog title={t('Tạo câu hỏi bằng AI')} onClose={onClose} busy={generate.isPending} size="lg"
      description={t('AI soạn câu hỏi theo chủ đề và tài liệu bạn đưa. Mọi câu đều phải được bạn xem lại trước khi nhập vào Kho cá nhân dưới dạng bản nháp.')}
      footer={<>
        <button type="button" className="btn btn-secondary" onClick={onClose} disabled={generate.isPending}>{t('Hủy')}</button>
        <button type="button" className="btn btn-primary" disabled={generate.isPending || !status.data?.available} aria-busy={generate.isPending}
          onClick={() => { setTouched(true); if (!Object.keys(problems).length) generate.mutate() }}>
          {generate.isPending ? t('Đang gửi…') : t('Tạo câu hỏi')}</button>
      </>}>
      <div className="stack">
        {status.isLoading && <span className="row small muted" style={{ gap: 8 }}><Spinner size={14} /> {t('Đang kiểm tra dịch vụ AI…')}</span>}
        {status.isError && <Notice tone="danger">{errorText(status.error)}</Notice>}
        {unavailable && <Notice tone="warning" title={t('Chưa có nguồn sinh câu hỏi')}>{t('Máy chủ chưa bật nguồn sinh câu hỏi nào. Liên hệ quản trị viên.')}</Notice>}
        {status.data?.providers && status.data.providers.length > 0 && <p className="xsmall muted" role="note">
          {t('Thứ tự dự phòng:')} {status.data.providers.map((p, i) => <span key={p.name}>{i > 0 && ' → '}
            <span style={{ textDecoration: p.available ? undefined : 'line-through' }}>{PROVIDER_LABEL[p.name] ?? p.name}</span>
            {!p.available && ` (${t('chưa cấu hình')})`}{p.available && p.coolingDown && ` (${t('đang tạm nghỉ')})`}</span>)}
          {'. '}{t('Nguồn nào lỗi hoặc bị giới hạn sẽ tự chuyển sang nguồn kế tiếp.')}</p>}
        {generate.isError && <Notice tone="danger" live>{errorText(generate.error)}</Notice>}
        <fieldset className="stack" disabled={!!unavailable || generate.isPending} style={{ border: 0, padding: 0, margin: 0 }}>
          <Field label={t('Chủ đề / mục tiêu kiến thức')} htmlFor="ai-topic" required error={err('topic')}
            hint={t('Ví dụ: “Phép cộng phân số khác mẫu, lớp 6”.')}>
            <input id="ai-topic" className="input" maxLength={500} value={topic} onChange={e => setTopic(e.target.value)} data-autofocus />
          </Field>
          <Field label={t('Tài liệu kiến thức (không bắt buộc)')} htmlFor="ai-material" error={err('material')}
            hint={t('Dán nội dung bài học. Có tài liệu thì AI chỉ hỏi những gì có trong đó. {0}/{1} ký tự.', { 0: material.length, 1: maxMaterial })}>
            <textarea id="ai-material" className="input" rows={6} value={material} onChange={e => setMaterial(e.target.value)} />
          </Field>
          <div className="grid-2">
            <SubjectCategoryFields subjectId={subjectId} categoryId={categoryId} onSubject={id => { setSubjectId(id); setCategoryId('') }} onCategory={setCategoryId} required />
          </div>
          {err('categoryId') && <p className="field-error">{err('categoryId')}</p>}
          <fieldset className="stack-sm" style={{ border: 0, padding: 0, margin: 0 }}>
            <legend className="field-label">{t('Loại câu hỏi')}</legend>
            <div className="row" style={{ gap: 16 }}>
              {TYPES.map(type => <label key={type} className="check small">
                <input type="checkbox" checked={types.includes(type)} onChange={e => toggle(type, e.target.checked)} /> {QUESTION_TYPE_LABEL[type]}
              </label>)}
            </div>
            {err('questionTypes') && <p className="field-error">{err('questionTypes')}</p>}
          </fieldset>
          <div className="grid-2">
            <Field label={t('Số câu')} htmlFor="ai-count" error={err('count')} hint={t('Tối đa {0} câu mỗi lần.', { 0: max })}>
              <input id="ai-count" type="number" className="input" min={1} max={max} value={count} onChange={e => setCount(Number(e.target.value))} />
            </Field>
            <Field label={t('Độ khó mong muốn')} htmlFor="ai-diff" hint={t('AI ước lượng lại độ khó từng câu; bạn sửa được khi xem lại.')}>
              <Select id="ai-diff" className="input" value={difficulty} onChange={e => setDifficulty(e.target.value as Difficulty)}>
                {Object.entries(DIFFICULTY_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
              </Select>
            </Field>
          </div>
          <div className="grid-2">
            <Field label={t('Ngôn ngữ câu hỏi')} htmlFor="ai-lang">
              <Select id="ai-lang" className="input" value={language} onChange={e => setLanguage(e.target.value as 'vi' | 'en')}>
                <option value="vi">Tiếng Việt</option><option value="en">English</option>
              </Select>
            </Field>
            <div className="stack-sm" style={{ alignSelf: 'end', paddingBottom: 6 }}>
              <label className="check small">
                <input type="checkbox" checked={useBank} onChange={e => setUseBank(e.target.checked)} /> {t('Dùng câu hỏi sẵn có trong danh mục làm mẫu')}
              </label>
              {status.data?.webKnowledge && <label className="check small">
                <input type="checkbox" checked={useWeb && !material.trim()} disabled={!!material.trim()} onChange={e => setUseWeb(e.target.checked)} /> {t('Tự tìm kiến thức trên Wikipedia khi không dán tài liệu')}
              </label>}
            </div>
          </div>
          <p className="xsmall muted">{t('Chỉ câu hỏi của chính bạn trong danh mục được gửi cho AI làm mẫu và để phát hiện câu trùng. Tài liệu và chủ đề được gửi tới dịch vụ AI bên ngoài.')}</p>
        </fieldset>
      </div>
    </Dialog>
  )
}

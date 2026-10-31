import { useRef, useState } from 'react'
import { FileUp, Link2 } from 'lucide-react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQuery } from '@tanstack/react-query'
import { importApi, type CognitiveLevel, type Difficulty } from './importApi'
import { PROVIDER_LABEL, aiProblems } from './importModel'
import type { QuestionType } from '../question-bank/questionApi'
import SubjectCategoryFields from '../question-bank/SubjectCategoryFields'
import { Dialog } from '../../components/Dialog'
import { Field, Notice, Spinner } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { DIFFICULTY_LABEL, QUESTION_TYPE_LABEL } from '../../lib/format'
import { t, useT } from '../../lib/i18n'
import Select from '../../components/Select'
import './import.css'

const TYPES: QuestionType[] = ['MULTIPLE_CHOICE', 'MULTIPLE_RESPONSE', 'TRUE_FALSE', 'TRUE_FALSE_GROUP', 'SHORT_ANSWER', 'ESSAY', 'SPEAKING']
/** Bloom's taxonomy as Vietnamese teachers name it (mức độ nhận thức). */
const BLOOM: { value: CognitiveLevel | ''; label: () => string }[] = [
  { value: '', label: () => t('Không chỉ định') },
  { value: 'REMEMBER', label: () => t('Nhớ – nhận biết') },
  { value: 'UNDERSTAND', label: () => t('Hiểu – thông hiểu') },
  { value: 'APPLY', label: () => t('Vận dụng') },
  { value: 'ANALYZE', label: () => t('Phân tích') },
  { value: 'EVALUATE', label: () => t('Đánh giá') },
  { value: 'CREATE', label: () => t('Sáng tạo') },
  { value: 'MIXED', label: () => t('Trộn nhiều mức') },
]
export interface AiPrefill { topic?: string; material?: string; categoryId?: string; questionTypes?: QuestionType[]; difficulty?: Difficulty }

/**
 * AI generation step: topic (+ optional knowledge text) → a staged job reviewed on the same page as a
 * document import. Nothing reaches the bank until the teacher reviews and imports it as a draft.
 */
export default function AiGenerateDialog({ onClose, prefill }: { onClose: () => void; prefill?: AiPrefill }) {
  useT()
  const navigate = useNavigate()
  const status = useQuery({ queryKey: ['ai-status'], queryFn: importApi.aiStatus, staleTime: 60_000 })
  const max = status.data?.maxQuestions ?? 20, maxMaterial = status.data?.maxMaterialChars ?? 20000
  const [topic, setTopic] = useState(prefill?.topic ?? '')
  const [material, setMaterial] = useState(prefill?.material ?? '')
  const [subjectId, setSubjectId] = useState('')
  const [categoryId, setCategoryId] = useState(prefill?.categoryId ?? '')
  const [count, setCount] = useState(5)
  const [types, setTypes] = useState<QuestionType[]>(prefill?.questionTypes ?? ['MULTIPLE_CHOICE'])
  const [difficulty, setDifficulty] = useState<Difficulty>(prefill?.difficulty ?? 'MEDIUM')
  const [bloom, setBloom] = useState<CognitiveLevel | ''>('')
  const fileInput = useRef<HTMLInputElement>(null)
  const extract = useMutation({ mutationFn: importApi.material, onSuccess: m => { setMaterial(m.text); fromUrl.reset() } })
  const [url, setUrl] = useState('')
  const fromUrl = useMutation({ mutationFn: importApi.materialFromUrl, onSuccess: m => {
    setMaterial(m.text); extract.reset()
    // An empty topic takes the page title, without the site name ("Quang hợp – Wikipedia tiếng Việt" → "Quang hợp").
    setTopic(cur => cur.trim() ? cur : m.fileName.split(/\s+[–—|·-]\s+/)[0].slice(0, 200))
  } })
  const fetchUrl = () => { if (url.trim() && !fromUrl.isPending) fromUrl.mutate(url.trim()) }
  const [language, setLanguage] = useState<'vi' | 'en'>('vi')
  const [useBank, setUseBank] = useState(true)
  const [useWeb, setUseWeb] = useState(true)
  const [touched, setTouched] = useState(false)
  const problems = aiProblems({ topic, categoryId, count, questionTypes: types, material }, max, maxMaterial)
  const err = (k: keyof typeof problems) => (touched ? problems[k] : undefined)
  const generate = useMutation({
    mutationFn: () => importApi.generate({ topic: topic.trim(), material: material.trim() || undefined, categoryId, count, questionTypes: types,
      difficulty, language, useBankExamples: useBank, useWebKnowledge: useWeb && !material.trim(), ...(bloom ? { cognitiveLevel: bloom } : {}) }),
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
          <div className="row" style={{ gap: 8, flexWrap: 'wrap', marginTop: -6 }}>
            <input ref={fileInput} type="file" hidden accept=".docx,.pdf,.png,.jpg,.jpeg" onChange={e => { const f = e.target.files?.[0]; e.target.value = ''; if (f) extract.mutate(f) }} />
            <button type="button" className="btn btn-secondary btn-sm" disabled={extract.isPending} aria-busy={extract.isPending} onClick={() => fileInput.current?.click()}>
              <FileUp size={14} aria-hidden /> {extract.isPending ? t('Đang đọc tệp…') : t('Lấy nội dung từ tệp (Word, PDF, ảnh)')}</button>
            {extract.data && <span className="xsmall muted" role="status">{t('Đã lấy chữ từ {0} ({1} trang{2}).', { 0: extract.data.fileName, 1: extract.data.pageCount, 2: extract.data.ocrPageCount ? t(', {0} trang nhận dạng ảnh', { 0: extract.data.ocrPageCount }) : '' })}
              {extract.data.truncated && ' ' + t('Tài liệu dài nên chỉ giữ phần đầu.')}</span>}
            {extract.isError && <span className="field-error">{errorText(extract.error)}</span>}
          </div>
          <div className="ai-url-row">
            <label htmlFor="ai-url" className="sr-only">{t('Đường dẫn trang web làm tài liệu')}</label>
            <input id="ai-url" className="input" type="url" inputMode="url" placeholder={t('Hoặc dán link bài học, bài báo, PDF trực tuyến…')} value={url}
              onChange={e => setUrl(e.target.value)} onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); fetchUrl() } }} />
            <button type="button" className="btn btn-secondary btn-sm" disabled={!url.trim() || fromUrl.isPending} aria-busy={fromUrl.isPending} onClick={fetchUrl}>
              <Link2 size={14} aria-hidden /> {fromUrl.isPending ? t('Đang đọc trang…') : t('Lấy nội dung từ link')}</button>
          </div>
          {fromUrl.data && <p className="xsmall muted" role="status" style={{ marginTop: -6 }}>{t('Đã lấy chữ từ trang “{0}”.', { 0: fromUrl.data.fileName })}
            {fromUrl.data.truncated && ' ' + t('Tài liệu dài nên chỉ giữ phần đầu.')} {t('Hãy đọc lại và xoá phần không liên quan trước khi sinh câu hỏi.')}</p>}
          {fromUrl.isError && <p className="field-error" style={{ marginTop: -6 }}>{errorText(fromUrl.error)}</p>}
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
          <Field label={t('Mức độ nhận thức (thang Bloom)')} htmlFor="ai-bloom" hint={t('AI soạn câu theo đúng mức: nhớ, hiểu, vận dụng, phân tích, đánh giá, sáng tạo.')}>
            <Select id="ai-bloom" className="input" value={bloom} onChange={e => setBloom(e.target.value as CognitiveLevel | '')}>
              {BLOOM.map(b => <option key={b.value} value={b.value}>{b.label()}</option>)}
            </Select>
          </Field>
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

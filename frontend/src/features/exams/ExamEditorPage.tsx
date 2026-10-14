import { useEffect, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowDown, ArrowUp, Check, LayoutList, Plus, Trash2 } from 'lucide-react'
import { examApi, type Exam, type ExamInput, type Policy } from './examApi'
import type { Question } from '../question-bank/questionApi'
import { bankApi } from '../banks/bankApi'
import PolicyFields from './PolicyFields'
import { legacyScheduleFields } from './examInput'
import { DEFAULT_POLICY, policyError } from './policy'
import QuestionPicker from './QuestionPicker'
import BlueprintDialog from './BlueprintDialog'
import { setAllPoints } from './builderModel'
import { ConfirmDialog } from '../../components/Dialog'
import { t, useT } from '../../lib/i18n'
import IntegrityFields from '../integrity/IntegrityFields'
import { integrityApi, type IntegrityPolicy } from '../integrity/integrityApi'
import { DISABLED_POLICY, NEW_EXAM_POLICY, counts, limitError, policySummary } from '../integrity/integrityModel'
import { EmptyState, ErrorState, Field, LoadingState, Notice, PageHeader } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { ACCESS_MODE_LABEL, DIFFICULTY_LABEL, EXAM_TYPE_LABEL, QUESTION_TYPE_LABEL, formatScore, shortId } from '../../lib/format'

interface Info {
  title: string; description: string; type: 'PRACTICE' | 'EXAM'; durationMinutes: number; passingScore: string
  shuffleQuestions: boolean; shuffleOptions: boolean
}
interface Item { questionId: string; weight: number }
const STEPS = ['Thông tin chung', 'Câu hỏi và điểm', 'Tham gia và giới hạn', 'Xem lại và lưu']
const EMPTY_INFO: Info = { title: '', description: '', type: 'EXAM', durationMinutes: 45, passingScore: '', shuffleQuestions: false, shuffleOptions: false }

export default function ExamEditorPage() {
  const { id } = useParams()
  useT()
  const existing = useQuery({ queryKey: ['exam', id], queryFn: () => examApi.get(id!), enabled: !!id })
  if (id && existing.isLoading) return <LoadingState rows={6} />
  if (id && existing.isError) return <ErrorState error={existing.error} onRetry={() => void existing.refetch()} />
  if (existing.data && existing.data.status !== 'DRAFT') {
    return <Notice tone="warning" title={t('Chỉ đề thi ở trạng thái Bản nháp mới chỉnh sửa được')}
      action={<Link className="btn btn-secondary btn-sm" to={`/exams/${id}`}>{t('Về trang đề thi')}</Link>}>
      {t('Đề thi đã xuất bản được cố định nội dung và phiên bản câu hỏi. Hãy tạo đề thi mới nếu cần thay đổi.')}
    </Notice>
  }
  return <Editor key={id ?? 'new'} exam={existing.data} />
}

function Editor({ exam }: { exam?: Exam }) {
  useT()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const [step, setStep] = useState(0)
  const [info, setInfo] = useState<Info>(exam ? {
    title: exam.title, description: exam.description ?? '', type: exam.type, durationMinutes: exam.durationMinutes,
    passingScore: exam.passingScore === null ? '' : String(exam.passingScore),
    shuffleQuestions: exam.shuffleQuestions, shuffleOptions: exam.shuffleOptions,
  } : EMPTY_INFO)
  const [items, setItems] = useState<Item[]>(exam ? exam.questions.map(q => ({ questionId: q.questionId, weight: q.weight })) : [])
  const [known, setKnown] = useState<Record<string, Question>>({})
  const [policy, setPolicy] = useState<Policy>(exam ? { accessMode: exam.accessMode, maxParticipants: exam.maxParticipants, maxConcurrentSessions: exam.maxConcurrentSessions, attemptLimit: exam.attemptLimit } : DEFAULT_POLICY)
  const [integrity, setIntegrity] = useState<IntegrityPolicy>(exam ? (exam.integrityPolicy ? { ...DISABLED_POLICY, ...exam.integrityPolicy } : DISABLED_POLICY) : NEW_EXAM_POLICY)
  const [picking, setPicking] = useState(false)
  const [bulkPoints, setBulkPoints] = useState('')
  const [clearing, setClearing] = useState(false)
  const [blueprint, setBlueprint] = useState(false)
  const [error, setError] = useState('')
  const [saving, setSaving] = useState(false)

  // Draft composition reads provider resources; published views keep their snapshots.
  const loaded = useQuery({ queryKey: ['questions', 'draft-resources', exam?.id, exam?.questionIds], queryFn: () => bankApi.eligibleBatch(exam!.questionIds), enabled: !!exam?.questionIds.length, retry: false })
  useEffect(() => {
    if (loaded.data) setKnown(k => ({ ...k, ...Object.fromEntries(loaded.data!.map(q => [q.id, q])) }))
  }, [loaded.data])

  const total = items.reduce((s, i) => s + (Number.isFinite(i.weight) ? i.weight : 0), 0)
  const passing = info.passingScore.trim() === '' ? null : Number(info.passingScore)

  function validate(target: number): string | null {
    if (target >= 0) {
      if (!info.title.trim()) return t('Nhập tên đề thi.')
      if (!Number.isInteger(info.durationMinutes) || info.durationMinutes < 1) return t('Thời lượng phải là số phút nguyên từ 1 trở lên.')
    }
    if (target >= 1) {
      if (items.length === 0) return t('Thêm ít nhất một câu hỏi đủ điều kiện.')
      if (items.length > 200) return t('Đề thi tối đa 200 câu hỏi.')
      if (items.some(i => !(i.weight > 0))) return t('Mỗi câu hỏi cần điểm lớn hơn 0.')
      if (passing !== null && (!Number.isFinite(passing) || passing < 0 || passing > total)) return t('Điểm đạt phải từ 0 đến tổng điểm ({total}).', { total: formatScore(total) })
    }
    if (target >= 2) return policyError(policy) ?? (counts(integrity) ? limitError(integrity.warningEventLimit) : null)
    return null
  }

  function go(next: number) {
    const problem = next > step ? validate(next - 1) : null
    setError(problem ?? '')
    if (!problem) setStep(next)
  }

  async function save() {
    const problem = validate(2)
    if (problem) { setError(problem); return }
    setSaving(true); setError('')
    const payload: ExamInput = {
      title: info.title.trim(), description: info.description.trim() || undefined, type: info.type, durationMinutes: info.durationMinutes,
      passingScore: passing, shuffleQuestions: info.shuffleQuestions, shuffleOptions: info.shuffleOptions,
      // The schedule lives on the Lịch tab; the legacy fields are passed through exactly as stored so editing never rewrites it.
      ...legacyScheduleFields(exam), questions: items,
    }
    let saved: Exam
    try {
      saved = exam ? await examApi.update(exam.id, payload) : await examApi.create(payload)
    } catch (e) {
      setError(errorText(e)); setSaving(false); return
    }
    let warning: string | undefined
    try {
      await examApi.policy(saved.id, { ...policy, joinCode: policy.accessMode === 'JOIN_CODE' ? policy.joinCode : undefined })
    } catch (e) {
      warning = t('Đã lưu đề thi nhưng chưa lưu được chính sách tham gia: {error}', { error: errorText(e) })
    }
    try {
      await integrityApi.savePolicy(saved.id, integrity)
    } catch (e) {
      warning = `${warning ? `${warning} ` : ''}${t('Chưa lưu được cấu hình giám sát trình duyệt: {error}', { error: errorText(e) })}`
    }
    await qc.invalidateQueries({ queryKey: ['exams'] })
    await qc.invalidateQueries({ queryKey: ['exam', saved.id] })
    navigate(`/exams/${saved.id}`, { state: { notice: warning ?? t(exam ? 'Đã lưu thay đổi.' : 'Đã tạo đề thi ở trạng thái Bản nháp.'), tone: warning ? 'warning' : 'success' } })
  }

  const move = (i: number, d: number) => setItems(list => { const next = [...list]; [next[i], next[i + d]] = [next[i + d], next[i]]; return next })

  return (
    <div className="stack-lg">
      <PageHeader title={exam ? t('Sửa đề thi: {title}', { title: exam.title }) : t('Tạo đề thi')}
        crumbs={[{ label: t('Đề thi'), to: '/exams' }, ...(exam ? [{ label: exam.title, to: `/exams/${exam.id}` }] : []), { label: t(exam ? 'Sửa' : 'Tạo mới') }]}
        description={t('Đề thi được lưu ở trạng thái Bản nháp. Bạn xuất bản và mở kỳ thi ở trang chi tiết đề.')} />

      <nav aria-label={t('Các bước')}>
        <ol className="steps">
          {STEPS.map((label, i) => (
            <li key={label} className="step" data-state={i < step ? 'done' : i === step ? 'current' : 'todo'} aria-current={i === step ? 'step' : undefined}>
              <button type="button" className="row" style={{ background: 'none', border: 0, padding: 0, cursor: 'pointer', color: 'inherit', font: 'inherit', gap: 8 }}
                onClick={() => go(i)}>
                <span className="step-dot">{i < step ? <Check size={14} aria-hidden /> : i + 1}</span>{t(label)}
              </button>
              {i < STEPS.length - 1 && <span className="step-sep" aria-hidden style={{ marginLeft: 12 }} />}
            </li>
          ))}
        </ol>
      </nav>

      <section className="card card-body stack-lg" aria-labelledby="step-title">
        <h2 id="step-title">{t(STEPS[step])}</h2>

        {step === 0 && <>
          <Field label={t('Tên đề thi')} htmlFor="exam-title" required>
            <input id="exam-title" className="input" maxLength={255} value={info.title} onChange={e => setInfo({ ...info, title: e.target.value })} />
          </Field>
          <Field label={t('Hướng dẫn cho thí sinh')} htmlFor="exam-desc" hint={t('Hiển thị ở phòng chờ trước khi bắt đầu.')}>
            <textarea id="exam-desc" className="input" rows={4} value={info.description} onChange={e => setInfo({ ...info, description: e.target.value })} />
          </Field>
          <div className="grid-2">
            <Field label={t('Loại đề')} htmlFor="exam-type">
              <select id="exam-type" className="input" value={info.type} onChange={e => setInfo({ ...info, type: e.target.value as Info['type'] })}>
                {Object.entries(EXAM_TYPE_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
              </select>
            </Field>
            <Field label={t('Thời lượng (phút)')} htmlFor="exam-duration" required hint={t('Thời gian tính từ lúc thí sinh bắt đầu; máy chủ kiểm soát hết giờ.')}>
              <input id="exam-duration" type="number" min={1} step={1} className="input" value={info.durationMinutes || ''} onChange={e => setInfo({ ...info, durationMinutes: Math.floor(Number(e.target.value)) })} />
            </Field>
            <div className="notice notice-info" style={{ gridColumn: '1 / -1' }}><span>{t('Lịch thi (giờ bắt đầu và kết thúc theo múi giờ của kỳ thi, cách tính hạn nộp, tự mở/đóng, nhắc) được đặt ở tab Lịch của đề sau khi lưu. Đề có thể không có lịch.')}</span></div>
          </div>
          <fieldset className="stack-sm">
            <legend className="field-label" style={{ marginBottom: 6 }}>{t('Hiển thị cho thí sinh')}</legend>
            <label className="check"><input type="checkbox" checked={info.shuffleQuestions} onChange={e => setInfo({ ...info, shuffleQuestions: e.target.checked })} />
              <span>{t('Đảo thứ tự câu hỏi')}<br /><span className="xsmall muted">{t('Mỗi lượt làm bài có thứ tự riêng, giữ nguyên khi tải lại trang.')}</span></span></label>
            <label className="check"><input type="checkbox" checked={info.shuffleOptions} onChange={e => setInfo({ ...info, shuffleOptions: e.target.checked })} />
              <span>{t('Đảo thứ tự lựa chọn')}<br /><span className="xsmall muted">{t('Áp dụng cho câu trắc nghiệm và Đúng/Sai.')}</span></span></label>
          </fieldset>
        </>}

        {step === 1 && <>
          <Notice tone="info">{t('Đề thi quyết định điểm cho từng câu. Khi xuất bản, nội dung và phiên bản của các câu hỏi được cố định; chỉnh sửa câu hỏi sau đó không ảnh hưởng đến đề.')}</Notice>
          {loaded.isError && <Notice tone="warning">{t('Không xác minh được các câu đã chọn: {error}. Máy chủ kiểm tra lại khi xuất bản.', { error: errorText(loaded.error) })}</Notice>}
          <div className="spread">
            <p className="small">{t('{n} câu hỏi · Tổng điểm {total}', { n: items.length, total: formatScore(total) })}</p>
            <span className="row" style={{ gap: 8 }}>
              <button type="button" className="btn btn-secondary" onClick={() => setBlueprint(true)}><LayoutList size={16} aria-hidden /> {t('Tạo theo khung đề')}</button>
              <button type="button" className="btn btn-secondary" onClick={() => setPicking(true)}><Plus size={16} aria-hidden /> {t('Thêm câu hỏi đủ điều kiện')}</button>
            </span>
          </div>
          {items.length > 1 && <div className="row" style={{ gap: 8 }}>
            <label className="row xsmall" style={{ gap: 6 }}>{t('Đặt cùng điểm cho mọi câu')}
              <input type="number" min={0.25} step={0.25} className="input" style={{ width: 90, minHeight: 34 }} value={bulkPoints} onChange={e => setBulkPoints(e.target.value)} /></label>
            <button type="button" className="btn btn-secondary btn-sm" disabled={!(Number(bulkPoints) > 0)} onClick={() => setItems(list => setAllPoints(list, Number(bulkPoints)))}>{t('Áp dụng')}</button>
            <button type="button" className="btn btn-danger-ghost btn-sm" style={{ marginLeft: 'auto' }} onClick={() => setClearing(true)}>{t('Bỏ tất cả câu')}</button>
          </div>}
          {items.length === 0 ? <div className="card"><EmptyState title={t('Chưa có câu hỏi')} description={t('Thêm câu hỏi đủ điều kiện từ Ngân hàng câu hỏi.')}
            action={<button type="button" className="btn btn-primary btn-sm" onClick={() => setPicking(true)}><Plus size={14} aria-hidden /> {t('Thêm câu hỏi')}</button>} /></div>
            : <ol className="card" style={{ listStyle: 'none', margin: 0, padding: 0, overflow: 'hidden' }}>
              {items.map((item, i) => {
                const q = known[item.questionId]
                return (
                  <li key={item.questionId} className="row" style={{ padding: '12px 16px', borderBottom: '1px solid var(--color-border)', alignItems: 'flex-start', flexWrap: 'nowrap' }}>
                    <span className="mono muted small" style={{ width: 28, paddingTop: 2 }}>{i + 1}.</span>
                    <div style={{ flex: 1, minWidth: 0 }}>
                      <div className="truncate-2 small" style={{ color: 'var(--color-ink)' }}>{q ? q.content : t('Câu hỏi {id}', { id: shortId(item.questionId) })}</div>
                      <div className="xsmall muted">{q ? `${t(QUESTION_TYPE_LABEL[q.questionType] ?? '')} · ${t(DIFFICULTY_LABEL[q.difficultyLevel] ?? '')} · v${q.questionVersion}${q.eligible !== true ? t(' · Cần kiểm tra lại điều kiện trước khi xuất bản') : ''}` : t('Không đọc được nội dung câu hỏi (có thể không thuộc quyền của bạn).')}</div>
                    </div>
                    <label className="row xsmall" style={{ flexWrap: 'nowrap', gap: 6 }}>{t('Điểm')}
                      <input type="number" min={0.25} step={0.25} className="input" style={{ width: 84, minHeight: 34 }} value={Number.isFinite(item.weight) ? item.weight : ''}
                        aria-label={t('Điểm câu {n}', { n: i + 1 })} onChange={e => setItems(list => list.map((x, n) => n === i ? { ...x, weight: Number(e.target.value) } : x))} />
                    </label>
                    <button type="button" className="btn btn-ghost btn-icon" aria-label={t('Đưa câu {n} lên', { n: i + 1 })} disabled={i === 0} onClick={() => move(i, -1)}><ArrowUp size={16} /></button>
                    <button type="button" className="btn btn-ghost btn-icon" aria-label={t('Đưa câu {n} xuống', { n: i + 1 })} disabled={i === items.length - 1} onClick={() => move(i, 1)}><ArrowDown size={16} /></button>
                    <button type="button" className="btn btn-danger-ghost btn-icon" aria-label={t('Gỡ câu {n}', { n: i + 1 })} onClick={() => setItems(list => list.filter((_, n) => n !== i))}><Trash2 size={16} /></button>
                  </li>
                )
              })}
            </ol>}
          <Field label={t('Điểm đạt (tùy chọn)')} htmlFor="exam-passing" hint={t('Từ 0 đến tổng điểm {total}. Để trống nếu không xét đạt/không đạt.', { total: formatScore(total) })}>
            <input id="exam-passing" type="number" min={0} step={0.25} className="input" style={{ maxWidth: 200 }} value={info.passingScore} onChange={e => setInfo({ ...info, passingScore: e.target.value })} />
          </Field>
        </>}

        {step === 2 && <>
          <PolicyFields value={policy} onChange={setPolicy} />
          {exam?.accessMode === 'JOIN_CODE' && <p className="small muted">{t('Mã tham gia hiện tại:')} <strong className="mono">{exam.code}</strong></p>}
          <p className="small muted">{t('Phân công thí sinh (khi chọn Được phân công) thực hiện ở trang chi tiết đề sau khi lưu.')}</p>
          <IntegrityFields value={integrity} onChange={setIntegrity} />
        </>}

        {step === 3 && (
          <dl className="meta">
            <div><dt>{t('Tên đề')}</dt><dd>{info.title}</dd></div>
            <div><dt>{t('Loại')}</dt><dd>{t(EXAM_TYPE_LABEL[info.type])}</dd></div>
            <div><dt>{t('Thời lượng')}</dt><dd>{t('{n} phút', { n: info.durationMinutes })}</dd></div>
            <div><dt>{t('Lịch thi')}</dt><dd>{t(exam?.startTime || exam?.endTime ? 'Giữ nguyên lịch hiện có (sửa ở tab Lịch)' : 'Đặt sau ở tab Lịch')}</dd></div>
            <div><dt>{t('Số câu / tổng điểm')}</dt><dd>{t('{n} câu · {points} điểm', { n: items.length, points: formatScore(total) })}</dd></div>
            <div><dt>{t('Điểm đạt')}</dt><dd>{passing === null ? t('Không đặt') : formatScore(passing)}</dd></div>
            <div><dt>{t('Cách tham gia')}</dt><dd>{t(ACCESS_MODE_LABEL[policy.accessMode])}</dd></div>
            <div><dt>{t('Giám sát trình duyệt')}</dt><dd>{policySummary(integrity)}</dd></div>
            <div><dt>{t('Giới hạn')}</dt><dd>{t('{people} người · {sessions} phiên đồng thời · {attempts} lượt/người', { people: policy.maxParticipants, sessions: policy.maxConcurrentSessions, attempts: policy.attemptLimit })}</dd></div>
          </dl>
        )}

        {error && <Notice tone="danger" live>{error}</Notice>}
        <div className="spread" style={{ borderTop: '1px solid var(--color-border)', paddingTop: 16 }}>
          <button type="button" className="btn btn-secondary" disabled={saving} onClick={() => step === 0 ? navigate(exam ? `/exams/${exam.id}` : '/exams') : go(step - 1)}>
            {t(step === 0 ? 'Hủy' : 'Quay lại')}
          </button>
          {step < STEPS.length - 1
            ? <button type="button" className="btn btn-primary" onClick={() => go(step + 1)}>{t('Tiếp tục')}</button>
            : <button type="button" className="btn btn-primary" disabled={saving} aria-busy={saving} onClick={() => void save()}>{t(saving ? 'Đang lưu…' : exam ? 'Lưu thay đổi' : 'Lưu bản nháp')}</button>}
        </div>
      </section>

      {clearing && <ConfirmDialog title={t('Bỏ tất cả câu khỏi đề?')} tone="danger" confirmLabel={t('Bỏ {n} câu', { n: items.length })}
        description={t('Chỉ bỏ khỏi bản nháp đang sửa; câu hỏi trong ngân hàng không bị ảnh hưởng. Chưa lưu cho đến khi bạn bấm Lưu.')}
        onConfirm={() => { setItems([]); setClearing(false) }} onCancel={() => setClearing(false)} />}
      {blueprint && <BlueprintDialog inExam={items.map(i => i.questionId)} onClose={() => setBlueprint(false)}
        onApply={picked => {
          setKnown(k => ({ ...k, ...Object.fromEntries(picked.map(p => [p.question.id, p.question])) }))
          setItems(list => [...list, ...picked.filter(p => !list.some(i => i.questionId === p.question.id)).map(p => ({ questionId: p.question.id, weight: p.weight }))])
          setBlueprint(false)
        }} />}
      {picking && <QuestionPicker excluded={items.map(i => i.questionId)} onClose={() => setPicking(false)}
        onAdd={qs => { setKnown(k => ({ ...k, ...Object.fromEntries(qs.map(q => [q.id, q])) })); setItems(list => [...list, ...qs.map(q => ({ questionId: q.id, weight: 1 }))]); setPicking(false) }} />}
    </div>
  )
}

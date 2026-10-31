import { useEffect, useMemo, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { Grid3x3, Save, Sparkles, Trash2, Wand2 } from 'lucide-react'
import { bankApi } from '../banks/bankApi'
import { BANK_TYPE_LABEL } from '../banks/bankModel'
import { questionBankApi } from '../question-bank/questionApi'
import {
  LEVELS, RATIO_PRESETS, available, cell, deleteTemplate, distribute, emptyMatrix, loadTemplates, matrixProblem, saveTemplate, setCell, shortages,
  toBlueprintRows, totals, levelLabel, levelShort, type Availability, type Matrix, type MatrixProblem, type SavedMatrix,
} from './matrixModel'
import type { BlueprintRowCfg } from './builderModel'
import { Dialog } from '../../components/Dialog'
import { Notice, Spinner } from '../../components/ui'
import Select from '../../components/Select'
import { useAuthStore } from '../../store/authStore'
import { errorText } from '../../lib/errors'
import { QUESTION_TYPE_LABEL, formatScore } from '../../lib/format'
import { t, useT } from '../../lib/i18n'


function problemText(p: MatrixProblem) {
  switch (p.kind) {
    case 'NO_TOPICS': return t('Chọn môn học và ít nhất một chủ đề.')
    case 'EMPTY': return t('Nhập số câu cho ít nhất một ô của ma trận.')
    case 'TOO_MANY': return t('Đề tối đa 200 câu (đang có {0} câu, tính cả câu đã có trong đề).', { 0: p.questions })
    case 'TOO_MANY_CELLS': return t('Tối đa 50 ô có câu hỏi (đang có {0}).', { 0: p.cells })
    case 'BAD_POINTS': return t('Điểm mỗi câu mức {0} phải lớn hơn 0.', { 0: levelShort(p.level) })
  }
}

/**
 * Exam matrix: topics × difficulty with how many eligible questions each cell has. Generating turns every non-empty
 * cell into a blueprint row, so the preview (swap, regenerate, shortfall per cell) is the usual blueprint preview.
 */
export default function MatrixDialog({ inExam, onGenerate, onClose }: {
  inExam: number; onGenerate: (rows: (BlueprintRowCfg & { label: string })[]) => void; onClose: () => void
}) {
  useT()
  const userId = useAuthStore(s => s.user?.id) ?? ''
  const [subjectId, setSubjectId] = useState('')
  const [matrix, setMatrix] = useState<Matrix>(emptyMatrix)
  const [total, setTotal] = useState(40)
  const [preset, setPreset] = useState(RATIO_PRESETS[0].key)
  const [notice, setNotice] = useState('')
  const [templates, setTemplates] = useState<SavedMatrix[]>(() => loadTemplates(userId))
  const [templateName, setTemplateName] = useState('')

  const subjects = useQuery({ queryKey: ['subjects'], queryFn: questionBankApi.getSubjects, staleTime: 300_000 })
  const categories = useQuery({ queryKey: ['categories', subjectId], queryFn: () => questionBankApi.getCategories(subjectId), enabled: !!subjectId, staleTime: 300_000 })
  const banks = useQuery({ queryKey: ['banks', 'picker'], queryFn: bankApi.allBanks })
  const topicIds = matrix.topics.map(x => x.categoryId)
  const counts = useQuery({
    queryKey: ['matrix-counts', topicIds, matrix.bankId, matrix.questionType],
    queryFn: () => bankApi.matrixCounts({ categoryIds: topicIds, ...(matrix.bankId ? { bankId: matrix.bankId } : {}), ...(matrix.questionType ? { questionType: matrix.questionType } : {}) }),
    enabled: topicIds.length > 0, staleTime: 30_000, retry: false,
  })
  const availability: Availability | undefined = useMemo(() => {
    if (!counts.data) return undefined
    const a: Availability = {}
    for (const r of counts.data) (a[r.categoryId] ??= {})[r.difficulty] = r.available
    return a
  }, [counts.data])

  // A new subject selects all of its topics (at most 50); the teacher unticks what the exam does not cover.
  useEffect(() => {
    if (!categories.data) return
    setMatrix(m => m.topics.length && m.topics.every(tp => categories.data!.some(c => c.id === tp.categoryId)) ? m
      : { ...m, topics: categories.data!.slice(0, 50).map(c => ({ categoryId: c.id, name: c.name })), counts: {} })
  }, [categories.data])

  const sum = totals(matrix)
  const problem = matrixProblem(matrix, inExam)
  const short = shortages(matrix, availability)
  const topicName = (id: string) => matrix.topics.find(x => x.categoryId === id)?.name ?? id

  function toggleTopic(id: string, name: string, on: boolean) {
    setMatrix(m => {
      if (!on) { const counts = { ...m.counts }; delete counts[id]; return { ...m, topics: m.topics.filter(x => x.categoryId !== id), counts } }
      if (m.topics.length >= 50 || m.topics.some(x => x.categoryId === id)) return m
      const order = categories.data?.map(c => c.id) ?? []
      return { ...m, topics: [...m.topics, { categoryId: id, name }].sort((a, b) => order.indexOf(a.categoryId) - order.indexOf(b.categoryId)) }
    })
  }

  function autoFill() {
    const ratio = RATIO_PRESETS.find(p => p.key === preset)!.ratio
    const r = distribute(matrix, Math.min(200, Math.max(1, Math.floor(total))), ratio, availability)
    setMatrix(r.matrix)
    setNotice(r.unplaced > 0 ? t('Ngân hàng không đủ câu: còn {0} câu chưa xếp được vào ma trận.', { 0: r.unplaced }) : t('Đã chia {0} câu theo tỉ lệ {1}.', { 0: total, 1: preset.replace(/-/g, '/') }))
  }

  function scaleTo10() {
    if (!sum.questions) return
    const each = Math.floor((10 / sum.questions) * 100) / 100
    setMatrix(m => ({ ...m, points: { EASY: each, MEDIUM: each, HARD: each } }))
  }

  // "Mô tả đề" → AI fills the cells over the ticked topics; the teacher still sees and edits every number.
  const [description, setDescription] = useState('')
  const plan = useMutation({
    mutationFn: () => questionBankApi.matrixPlan({ description: description.trim(), questionType: matrix.questionType,
      topics: matrix.topics.map(tp => ({ categoryId: tp.categoryId, name: tp.name, available: availability?.[tp.categoryId] ?? {} })) }),
    onSuccess: r => {
      setMatrix(m => {
        const counts: Matrix['counts'] = {}
        for (const c of r.cells) (counts[c.categoryId] ??= {})[c.level as typeof LEVELS[number]] = c.count
        return { ...m, counts, points: { ...m.points, ...r.points } }
      })
    },
  })

  function save() {
    const name = templateName.trim()
    if (!name || !userId) return
    setTemplates(saveTemplate(userId, { name, savedAt: new Date().toISOString(), matrix, subjectId }))
    setNotice(t('Đã lưu ma trận “{0}” trên trình duyệt này.', { 0: name }))
  }

  function load(name: string) {
    const tpl = templates.find(x => x.name === name)
    if (!tpl) return
    setSubjectId(tpl.subjectId ?? ''); setMatrix(tpl.matrix); setTemplateName(tpl.name)
    setNotice(t('Đã mở ma trận “{0}”.', { 0: tpl.name }))
  }

  return (
    <Dialog size="lg" title={t('Tạo đề theo ma trận')} onClose={onClose}
      description={t('Ma trận = số câu theo từng chủ đề và mức độ. Mỗi ô chỉ lấy câu đúng chủ đề và mức độ đó; thiếu thì báo thiếu chứ không lấy bù. Bước sau cho xem trước, đổi từng câu rồi mới thêm vào đề.')}
      footer={<>
        <span className="small muted" style={{ marginRight: 'auto' }} role="status">{t('{0} câu · {1} điểm', { 0: sum.questions, 1: formatScore(sum.points) })}</span>
        <button type="button" className="btn btn-secondary" onClick={onClose}>{t('Hủy')}</button>
        <button type="button" className="btn btn-primary" disabled={!!problem} onClick={() => onGenerate(toBlueprintRows(matrix, levelLabel))}>
          <Grid3x3 size={16} aria-hidden /> {t('Tạo đề theo ma trận')}</button>
      </>}>
      <div className="stack">
        <div className="matrix-filters">
          <div className="field"><label className="field-label" htmlFor="mx-subject">{t('Môn học')}</label>
            <Select id="mx-subject" className="input" value={subjectId} onChange={e => { setSubjectId(e.target.value); setMatrix(m => ({ ...m, topics: [], counts: {} })) }}>
              <option value="">{subjects.isLoading ? t('Đang tải…') : t('Chọn môn học')}</option>
              {subjects.data?.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
            </Select></div>
          <div className="field"><label className="field-label" htmlFor="mx-type">{t('Loại câu hỏi')}</label>
            <Select id="mx-type" className="input" value={matrix.questionType ?? ''} onChange={e => setMatrix(m => ({ ...m, questionType: e.target.value || undefined }))}>
              <option value="">{t('Mọi loại')}</option>{Object.entries(QUESTION_TYPE_LABEL).map(([v, l]) => <option key={v} value={v}>{t(l)}</option>)}
            </Select></div>
          <div className="field"><label className="field-label" htmlFor="mx-bank">{t('Ngân hàng')}</label>
            <Select id="mx-bank" className="input" value={matrix.bankId ?? ''} onChange={e => setMatrix(m => ({ ...m, bankId: e.target.value || undefined }))}>
              <option value="">{t('Tất cả ngân hàng dùng được')}</option>
              {(banks.data ?? []).map(b => <option key={b.id} value={b.id}>{BANK_TYPE_LABEL[b.type]} · {b.name}</option>)}
            </Select></div>
          {templates.length > 0 && <div className="field"><label className="field-label" htmlFor="mx-tpl">{t('Ma trận đã lưu')}</label>
            <Select id="mx-tpl" className="input" value="" onChange={e => load(e.target.value)}>
              <option value="">{t('Chọn để mở…')}</option>{templates.map(x => <option key={x.name} value={x.name}>{x.name}</option>)}
            </Select></div>}
        </div>

        {subjectId && categories.data && categories.data.length > 0 && <fieldset className="matrix-topics">
          <legend className="field-label">{t('Chủ đề trong đề ({0}/{1})', { 0: matrix.topics.length, 1: categories.data.length })}</legend>
          {categories.data.map(c => <label key={c.id} className="check small"><input type="checkbox" checked={topicIds.includes(c.id)}
            onChange={e => toggleTopic(c.id, c.name, e.target.checked)} /> <span>{c.name}</span></label>)}
        </fieldset>}
        {subjectId && categories.data?.length === 0 && <Notice tone="info">{t('Môn này chưa có chủ đề (danh mục). Tạo danh mục trong Kho câu hỏi trước.')}</Notice>}

        {matrix.topics.length > 0 && <div className="mx-ai">
          <label className="field-label" htmlFor="mx-desc"><Sparkles size={14} aria-hidden /> {t('Mô tả đề, AI điền ma trận giúp bạn (không bắt buộc)')}</label>
          <div className="mx-ai-row">
            <textarea id="mx-desc" className="input" rows={2} maxLength={1000} value={description} onChange={e => setDescription(e.target.value)}
              placeholder={t('Ví dụ: đề 45 phút, 10 câu dễ và 8 câu trung bình về hàm số, 4 câu khó về logarit, thang điểm 10')} />
            <button type="button" className="btn btn-secondary" disabled={!description.trim() || plan.isPending} aria-busy={plan.isPending} onClick={() => plan.mutate()}>
              <Sparkles size={16} aria-hidden /> {plan.isPending ? t('AI đang đọc…') : t('Điền ma trận')}</button>
          </div>
          {plan.isError && <span className="field-error">{errorText(plan.error)}</span>}
          {plan.data && <p className="small" role="status" style={{ margin: 0 }}>{plan.data.notes.length ? plan.data.notes.join(' ') : t('Đã điền ma trận; kiểm tra lại các ô bên dưới.')}</p>}
        </div>}

        {matrix.topics.length > 0 && <>
          <div className="matrix-quick">
            <label className="field"><span className="field-label">{t('Tổng số câu')}</span>
              <input className="input" type="number" min={1} max={200} value={total} onChange={e => setTotal(Number(e.target.value))} /></label>
            <label className="field"><span className="field-label">{t('Tỉ lệ Dễ/TB/Khó')}</span>
              <Select className="input" value={preset} onChange={e => setPreset(e.target.value)}>
                {RATIO_PRESETS.map(p => <option key={p.key} value={p.key}>{p.key.replace(/-/g, ' / ')} %</option>)}
              </Select></label>
            <button type="button" className="btn btn-secondary" onClick={autoFill} disabled={counts.isLoading}><Wand2 size={15} aria-hidden /> {t('Chia tự động')}</button>
            <button type="button" className="btn btn-ghost" onClick={scaleTo10} disabled={!sum.questions}>{t('Chia đều thang 10 điểm')}</button>
          </div>

          <div className="table-wrap">
            <table className="table matrix-table">
              <thead><tr><th scope="col">{t('Chủ đề')}</th>{LEVELS.map(l => <th key={l} scope="col">{levelLabel(l)}</th>)}<th scope="col">{t('Tổng')}</th></tr></thead>
              <tbody>{matrix.topics.map(tp => <tr key={tp.categoryId}>
                <th scope="row" className="small">{tp.name}</th>
                {LEVELS.map(l => {
                  const v = cell(matrix, tp.categoryId, l), have = available(availability, tp.categoryId, l)
                  const over = !!availability && v > have
                  return <td key={l}><div className="matrix-cell" data-over={over || undefined}>
                    <input type="number" min={0} max={200} className="input" aria-label={t('{0}, {1}: số câu', { 0: tp.name, 1: levelShort(l) })}
                      value={v || ''} placeholder="0" onChange={e => setMatrix(m => setCell(m, tp.categoryId, l, Number(e.target.value)))} />
                    <span className="xsmall muted">{counts.isLoading ? <Spinner size={10} /> : availability ? t('có {0}', { 0: have }) : ''}</span>
                  </div></td>
                })}
                <td className="mono">{sum.perTopic[tp.categoryId] || 0}</td>
              </tr>)}</tbody>
              <tfoot>
                <tr><th scope="row">{t('Số câu')}</th>{LEVELS.map(l => <td key={l} className="mono">{sum.perLevel[l]}</td>)}<td className="mono"><strong>{sum.questions}</strong></td></tr>
                <tr><th scope="row">{t('Điểm mỗi câu')}</th>{LEVELS.map(l => <td key={l}>
                  <input type="number" min={0.01} step={0.05} className="input matrix-points" aria-label={t('Điểm mỗi câu mức {0}', { 0: levelShort(l) })}
                    value={matrix.points[l]} onChange={e => setMatrix(m => ({ ...m, points: { ...m.points, [l]: Number(e.target.value) } }))} /></td>)}<td /></tr>
                <tr><th scope="row">{t('Điểm (tỉ lệ)')}</th>{LEVELS.map(l => <td key={l} className="small">{formatScore(sum.pointsPerLevel[l])} <span className="muted">({sum.percentPerLevel[l]}%)</span></td>)}
                  <td className="mono"><strong>{formatScore(sum.points)}</strong></td></tr>
              </tfoot>
            </table>
          </div>
          {counts.isError && <Notice tone="warning">{t('Không đếm được số câu có sẵn: {0}', { 0: errorText(counts.error) })}</Notice>}
          {short.length > 0 && <Notice tone="warning" title={t('Một số ô cần nhiều câu hơn ngân hàng đang có')}>
            {short.slice(0, 6).map(([id, l, missing]) => t('{0} · {1}: thiếu {2}', { 0: topicName(id), 1: levelShort(l), 2: missing })).join('; ')}{short.length > 6 ? '…' : ''}.
            {' '}{t('Vẫn tạo được; bản xem trước sẽ đánh dấu ô thiếu.')}</Notice>}
          <div className="row matrix-save" style={{ gap: 8, flexWrap: 'wrap' }}>
            <input className="input" style={{ maxWidth: 260 }} placeholder={t('Tên ma trận, ví dụ: Giữa kỳ 1 – Toán 10')} aria-label={t('Tên ma trận')}
              value={templateName} maxLength={80} onChange={e => setTemplateName(e.target.value)} />
            <button type="button" className="btn btn-ghost btn-sm" disabled={!templateName.trim()} onClick={save}><Save size={14} aria-hidden /> {t('Lưu ma trận')}</button>
            {templates.some(x => x.name === templateName.trim()) && <button type="button" className="btn btn-danger-ghost btn-sm"
              onClick={() => { setTemplates(deleteTemplate(userId, templateName.trim())); setNotice(t('Đã xóa ma trận đã lưu.')) }}><Trash2 size={14} aria-hidden /> {t('Xóa ma trận đã lưu')}</button>}
          </div>
        </>}
        {notice && <p className="small" role="status">{notice}</p>}
        {problem && matrix.topics.length > 0 && <p className="field-error">{problemText(problem)}</p>}
      </div>
    </Dialog>
  )
}

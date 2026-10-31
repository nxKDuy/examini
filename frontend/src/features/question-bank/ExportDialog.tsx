import { useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Download } from 'lucide-react'
import api from '../../lib/api'
import { questionBankApi, type Filters, type Question } from './questionApi'
import { exportFile, type ExportFormat } from './exportModel'
import { Dialog } from '../../components/Dialog'
import { Notice } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { t, useT } from '../../lib/i18n'

const MAX = 500
const FORMATS: { value: ExportFormat; label: () => string; hint: () => string }[] = [
  { value: 'csv', label: () => t('Excel / CSV (mẫu EXAMINI)'), hint: () => t('Mở bằng Excel; cùng cột với file mẫu nên nhập lại được vào EXAMINI.') },
  { value: 'moodle', label: () => t('Moodle XML'), hint: () => t('Nhập vào ngân hàng câu hỏi Moodle (mọi loại câu; câu Nói thành Tự luận).') },
  { value: 'qti', label: () => t('QTI 2.1 (ZIP)'), hint: () => t('Chuẩn quốc tế IMS QTI 2.1 cho TAO, Canvas, Inspera, ONYX…: mọi loại câu kèm đáp án và cách chấm; tự luận và câu Nói để chấm tay.') },
  { value: 'aiken', label: () => t('Aiken (TXT)'), hint: () => t('Định dạng văn bản đơn giản cho trắc nghiệm một đáp án; Moodle, Canvas và nhiều nền tảng đọc được.') },
]

/** Exports the questions matching the current filters (at most 500), with answer keys, in a chosen format. */
export default function ExportDialog({ filters, onClose }: { filters: Filters; onClose: () => void }) {
  useT()
  const [format, setFormat] = useState<ExportFormat>('csv')
  const [done, setDone] = useState<{ count: number; skipped: number } | null>(null)
  const run = useMutation({
    mutationFn: async () => {
      const ids: string[] = []
      for (let page = 0; ids.length < MAX; page++) {
        const p = await questionBankApi.getQuestions({ ...filters, page, size: 50 })
        ids.push(...p.content.map(q => q.id))
        if (page + 1 >= p.totalPages) break
      }
      const full: Question[] = []
      for (let i = 0; i < Math.min(ids.length, MAX); i += 200) {
        const chunk = ids.slice(i, Math.min(i + 200, MAX))
        full.push(...(await api.post<Question[]>('/questions/batch', chunk, { params: { eligible: false, includeAnswers: true } })).data)
      }
      const file = exportFile(format, full)
      const url = URL.createObjectURL(new Blob([file.body], { type: file.type }))
      const a = document.createElement('a'); a.href = url; a.download = file.name
      document.body.appendChild(a); a.click(); a.remove()
      window.setTimeout(() => URL.revokeObjectURL(url), 5000)
      return { count: full.length - file.skipped, skipped: file.skipped }
    },
    onSuccess: setDone,
  })
  return (
    <Dialog title={t('Xuất câu hỏi')} onClose={onClose} busy={run.isPending}
      description={t('Xuất các câu đang hiện theo bộ lọc (tối đa {0} câu), kèm đáp án. Hình ảnh và âm thanh không đi kèm tệp xuất.', { 0: MAX })}
      footer={<>
        <button type="button" className="btn btn-secondary" onClick={onClose} disabled={run.isPending}>{t('Đóng')}</button>
        <button type="button" className="btn btn-primary" disabled={run.isPending} aria-busy={run.isPending} onClick={() => { setDone(null); run.mutate() }}>
          <Download size={16} aria-hidden /> {run.isPending ? t('Đang chuẩn bị…') : t('Tải xuống')}</button>
      </>}>
      <div className="stack">
      <fieldset className="stack-sm" style={{ border: 0, padding: 0, margin: 0 }}>
        <legend className="field-label">{t('Định dạng')}</legend>
        {FORMATS.map(f => <label key={f.value} className="choice">
          <input type="radio" name="export-format" checked={format === f.value} onChange={() => setFormat(f.value)} />
          <span><strong style={{ color: 'var(--color-ink)' }}>{f.label()}</strong><br /><span className="xsmall muted">{f.hint()}</span></span>
        </label>)}
      </fieldset>
      {run.isError && <Notice tone="danger" live>{errorText(run.error)}</Notice>}
      {done && <Notice tone="success" live>{t('Đã xuất {0} câu.', { 0: done.count })}{done.skipped ? ' ' + t('{0} câu không hợp định dạng Aiken nên bị bỏ qua.', { 0: done.skipped }) : ''}</Notice>}
      </div>
    </Dialog>
  )
}

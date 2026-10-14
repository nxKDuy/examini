import type { IntegrityPolicy } from './integrityApi'
import { MAX_LIMIT, MIN_LIMIT, MONITORS, counts, limitError } from './integrityModel'
import { Field } from '../../components/ui'
import { useT } from '../../lib/i18n'

/** "Giám sát trình duyệt" for a DRAFT Exam. Frozen at Publish; signals are evidence, never proof. */
export default function IntegrityFields({ value, onChange }: { value: IntegrityPolicy; onChange: (p: IntegrityPolicy) => void }) {
  const t = useT()
  const error = counts(value) ? limitError(value.warningEventLimit) : null
  return (
    <fieldset className="stack" style={{ borderTop: '1px solid var(--color-border)', paddingTop: 16 }}>
      <legend className="field-label" style={{ marginBottom: 4 }}>{t('Giám sát trình duyệt')}</legend>
      <p className="small muted">{t('Trình duyệt của thí sinh báo các tín hiệu dưới đây. Tín hiệu là dữ liệu ghi nhận, không phải kết luận gian lận. Cấu hình được cố định khi xuất bản.')}</p>
      <div className="grid-2">
        {MONITORS.map(m => (
          <label key={m.key} className="check">
            <input type="checkbox" checked={value[m.key] as boolean} onChange={e => onChange({ ...value, [m.key]: e.target.checked })} />
            <span>{t(m.label)}<br /><span className="xsmall muted">{t(m.hint)}</span></span>
          </label>
        ))}
      </div>
      {counts(value) && <>
        <Field label={t('Số tín hiệu tối đa trước khi tự nộp')} htmlFor="integrity-limit" required error={error ?? undefined}
          hint={t('Từ {min} đến {max}, mặc định 5. Khi chạm ngưỡng thí sinh được cảnh báo; vượt ngưỡng thì chỉ lượt làm bài đó được tự động nộp.', { min: MIN_LIMIT, max: MAX_LIMIT })}>
          <input id="integrity-limit" type="number" min={MIN_LIMIT} max={MAX_LIMIT} step={1} className="input" style={{ maxWidth: 160 }}
            value={Number.isFinite(value.warningEventLimit) ? value.warningEventLimit : ''} onChange={e => onChange({ ...value, warningEventLimit: Math.floor(Number(e.target.value)) })} />
        </Field>
        <label className="check">
          <input type="checkbox" checked={value.warningPolicy === 'WARN_ONLY'} onChange={e => onChange({ ...value, warningPolicy: e.target.checked ? 'WARN_ONLY' : 'LOG_ONLY' })} />
          <span>{t('Hiện số tín hiệu đã ghi nhận cho thí sinh')}<br /><span className="xsmall muted">{t('Cảnh báo khi chạm ngưỡng luôn hiển thị.')}</span></span>
        </label>
      </>}
    </fieldset>
  )
}

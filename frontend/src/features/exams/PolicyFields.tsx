import type { AccessMode, Policy } from './examApi'
import { PLATFORM_CONCURRENCY_LIMIT } from './policy'
import { Field } from '../../components/ui'
import { useT, i18nMap } from '../../lib/i18n'

const MODES: { value: AccessMode; title: string; description: string }[] = i18nMap([
  { value: 'ASSIGNED', title: 'Được phân công', description: 'Chỉ thí sinh được giáo viên phân công mới vào được kỳ thi.' },
  { value: 'JOIN_CODE', title: 'Mã tham gia', description: 'Người dùng EXAMINI đã đăng nhập nhập đúng mã sẽ tham gia được.' },
  { value: 'PUBLIC', title: 'Công khai', description: 'Mọi người dùng EXAMINI đã đăng nhập đều thấy và tham gia được. Không cho phép truy cập ẩn danh.' },
])

export default function PolicyFields({ value, onChange, disabled }: { value: Policy; onChange: (p: Policy) => void; disabled?: boolean }) {
  const t = useT()
  const num = (key: 'maxParticipants' | 'maxConcurrentSessions' | 'attemptLimit') => (e: React.ChangeEvent<HTMLInputElement>) =>
    onChange({ ...value, [key]: e.target.value === '' ? 0 : Math.floor(Number(e.target.value)) })
  return (
    <div className="stack-lg">
      <fieldset className="stack-sm" disabled={disabled}>
        <legend className="field-label" style={{ marginBottom: 6 }}>{t('Cách thí sinh tham gia')}</legend>
        {MODES.map(m => (
          <label key={m.value} className="choice">
            <input type="radio" name="access-mode" value={m.value} checked={value.accessMode === m.value} onChange={() => onChange({ ...value, accessMode: m.value })} />
            <span><strong style={{ color: 'var(--color-ink)' }}>{t(m.title)}</strong><br /><span className="xsmall muted">{t(m.description)}</span></span>
          </label>
        ))}
      </fieldset>
      {value.accessMode === 'JOIN_CODE' && (
        <Field label={t('Mã tham gia')} htmlFor="policy-code" hint={t('Để trống để giữ mã hệ thống đã tạo. 4 đến 50 ký tự: chữ, số, _ hoặc -.')}>
          <input id="policy-code" className="input" style={{ maxWidth: 320 }} maxLength={50} disabled={disabled} value={value.joinCode ?? ''} spellCheck={false}
            onChange={e => onChange({ ...value, joinCode: e.target.value || undefined })} />
        </Field>
      )}
      <div className="grid-2" style={{ gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 200px), 1fr))' }}>
        <Field label={t('Số người tham gia tối đa')} htmlFor="policy-participants" hint={t('Tổng số thí sinh khác nhau được tham gia kỳ thi.')}>
          <input id="policy-participants" type="number" min={1} step={1} className="input" disabled={disabled} value={value.maxParticipants || ''} onChange={num('maxParticipants')} />
        </Field>
        <Field label={t('Số phiên làm bài đồng thời')} htmlFor="policy-concurrent"
          hint={t('Số lượt đang làm bài cùng lúc. Khi đạt giới hạn, thí sinh mới phải chờ. Đây là giới hạn nghiệp vụ, không phải cam kết tải hệ thống.')}>
          <input id="policy-concurrent" type="number" min={1} max={PLATFORM_CONCURRENCY_LIMIT} step={1} className="input" disabled={disabled}
            value={value.maxConcurrentSessions || ''} onChange={num('maxConcurrentSessions')} />
        </Field>
        <Field label={t('Số lượt làm bài mỗi thí sinh')} htmlFor="policy-attempts" hint={t('Lượt đang làm dở được tiếp tục, không tính thêm lượt.')}>
          <input id="policy-attempts" type="number" min={1} step={1} className="input" disabled={disabled} value={value.attemptLimit || ''} onChange={num('attemptLimit')} />
        </Field>
      </div>
    </div>
  )
}

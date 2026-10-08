import { useState } from 'react'
import { Plus, X } from 'lucide-react'
import './calendar.css'
import { MAX_OFFSET, MAX_REMINDERS, MIN_OFFSET, REMINDER_PRESETS, customToMinutes, normalizeReminders, offsetLabel, reminderIssue, reminderSend } from './scheduleModel'
import { formatDateTimeIn, zoneLabel } from '../../lib/zone'

export default function ReminderEditor({ value, off, onChange, startMs, tz, nowMs, disabled }: {
  value: number[]; off: boolean; onChange: (value: number[], off: boolean) => void; startMs: number | null; tz: string; nowMs: number; disabled?: boolean
}) {
  const [amount, setAmount] = useState('')
  const [unit, setUnit] = useState<'minute' | 'hour' | 'day'>('hour')
  const [error, setError] = useState('')
  const locked = disabled || off
  const list = normalizeReminders(value)
  const toggle = (min: number) => onChange(list.includes(min) ? list.filter(x => x !== min) : normalizeReminders([...list, min]), false)

  function addCustom() {
    const n = Number(amount), min = customToMinutes(n, unit)
    if (!amount.trim() || !Number.isFinite(n) || n <= 0) return setError('Nhập số lớn hơn 0.')
    const issue = reminderIssue(normalizeReminders([...list, min]))
    if (min < MIN_OFFSET || min > MAX_OFFSET) return setError(`Mốc nhắc từ ${MIN_OFFSET} phút đến 30 ngày.`)
    if (list.includes(min)) return setError('Mốc này đã có.')
    if (issue) return setError(issue)
    setError(''); setAmount(''); onChange(normalizeReminders([...list, min]), false)
  }

  return (
    <fieldset className="stack-sm" disabled={disabled}>
      <legend className="field-label" style={{ marginBottom: 6 }}>Nhắc trước giờ bắt đầu</legend>
      <label className="check"><input type="checkbox" checked={off} onChange={e => onChange(e.target.checked ? [] : list.length ? list : [1440, 15], e.target.checked)} />
        <span>Tắt nhắc cho kỳ thi này</span></label>
      <div className="row" role="group" aria-label="Mốc nhắc nhanh">
        {REMINDER_PRESETS.map(min => (
          <button key={min} type="button" className="rem-chip" aria-pressed={list.includes(min)} disabled={locked || (!list.includes(min) && list.length >= MAX_REMINDERS)} onClick={() => toggle(min)}>{offsetLabel(min)}</button>
        ))}
      </div>
      <div className="row" style={{ alignItems: 'flex-end' }}>
        <div className="field" style={{ width: 110 }}>
          <label className="field-label" htmlFor="rem-amount">Mốc tùy chọn</label>
          <input id="rem-amount" type="number" min={1} step="any" className="input" value={amount} disabled={locked} onChange={e => { setAmount(e.target.value); setError('') }}
            onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); addCustom() } }} aria-describedby="rem-error" />
        </div>
        <div className="field" style={{ width: 120 }}>
          <label className="field-label" htmlFor="rem-unit">Đơn vị</label>
          <select id="rem-unit" className="input" value={unit} disabled={locked} onChange={e => setUnit(e.target.value as typeof unit)}>
            <option value="minute">phút</option><option value="hour">giờ</option><option value="day">ngày</option>
          </select>
        </div>
        <button type="button" className="btn btn-secondary" disabled={locked} onClick={addCustom}><Plus size={14} aria-hidden /> Thêm mốc</button>
      </div>
      {error && <span id="rem-error" className="field-error" role="alert">{error}</span>}
      {off ? <p className="small muted">Nhắc đang tắt: không gửi nhắc trước giờ bắt đầu.</p>
        : list.length === 0 ? <p className="small muted">Chưa có mốc nhắc nào.</p>
        : (
          <ul className="rem-list" aria-label="Các mốc nhắc đã chọn">
            {list.map(min => {
              const send = startMs === null ? null : reminderSend(startMs, min, nowMs)
              return (
                <li key={min}>
                  <span><strong>Trước {offsetLabel(min)}</strong>{' '}
                    {send === null ? <span className="muted">· cần giờ bắt đầu để lập nhắc</span>
                      : send.past ? <span className="rem-past">· {formatDateTimeIn(send.at, tz)} {zoneLabel(send.at, tz)}</span>
                      : <span className="muted">· sẽ gửi lúc {formatDateTimeIn(send.at, tz)} {zoneLabel(send.at, tz)}</span>}
                    {send?.past && <span className="muted"> (đã qua, không gửi bù)</span>}
                  </span>
                  <button type="button" className="btn btn-ghost btn-icon" aria-label={`Bỏ mốc nhắc trước ${offsetLabel(min)}`} disabled={disabled} onClick={() => onChange(list.filter(x => x !== min), false)}><X size={16} /></button>
                </li>
              )
            })}
          </ul>
        )}
      <p className="field-hint">Tối đa {MAX_REMINDERS} mốc, mỗi mốc từ 1 phút đến 30 ngày. Mốc đã qua tại thời điểm lưu sẽ không được gửi bù; đổi lịch sẽ lập lại các mốc theo giờ mới.</p>
    </fieldset>
  )
}

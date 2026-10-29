import { useMemo, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import './calendar.css'
import { calendarApi, type DeadlineRule, type Schedule } from './calendarApi'
import { RULE_HELP, RULE_LABEL } from './calendarModel'
import { changeZone, chooseOption, fieldIssue, initForm, isChanged, setWall, toInput, validateForm, type Field, type FormState } from './scheduleModel'
import ReminderEditor from './ReminderEditor'
import { useNow } from './useCalendar'
import { Dialog } from '../../components/Dialog'
import { ErrorState, Field as FormField, LoadingState, Notice } from '../../components/ui'
import { errorInfo } from '../../lib/errors'
import { formatDateTimeIn, formatOffset, viewerZone, zoneLabel, zoneOptions } from '../../lib/zone'
import { t, useT } from '../../lib/i18n'
import Select from '../../components/Select'

const RULES: DeadlineRule[] = ['FULL_DURATION', 'CAPPED_AT_END', 'REQUIRE_FULL_WINDOW']

/** Loads the current schedule (it carries the revision), then edits it in a reviewable, revision-checked form. */
export default function ScheduleDialog({ examId, examTitle, status, durationMinutes, onClose, onSaved }: {
  examId: string; examTitle: string; status: string; durationMinutes?: number; onClose: () => void; onSaved?: (s: Schedule) => void
}) {
  useT()
  const [generation, setGeneration] = useState(0)
  const q = useQuery({
    queryKey: ['schedule', examId, generation],
    queryFn: async () => { const schedule = await calendarApi.getSchedule(examId); return { schedule, clockOffset: Date.parse(schedule.serverNow) - Date.now() } },
    staleTime: 0, gcTime: 0,
  })
  return (
    <Dialog size="lg" title={t('Lịch thi: {0}', { 0: examTitle })} onClose={onClose}
      description={t('Giờ nhập theo múi giờ của kỳ thi. Máy chủ lưu thời điểm UTC và kiểm tra quyền, trạng thái, phiên bản lịch.')}>
      {q.isLoading ? <LoadingState rows={4} /> : q.isError || !q.data ? <ErrorState error={q.error} onRetry={() => void q.refetch()} />
        : <ScheduleForm key={`${generation}-${q.data.schedule.scheduleRevision}`} schedule={q.data.schedule} clockOffset={q.data.clockOffset} status={status} durationMinutes={durationMinutes}
            onClose={onClose} onSaved={onSaved} onReload={() => setGeneration(g => g + 1)} />}
    </Dialog>
  )
}

function ScheduleForm({ schedule, clockOffset, status, durationMinutes, onClose, onSaved, onReload }: {
  schedule: Schedule; clockOffset: number; status: string; durationMinutes?: number; onClose: () => void; onSaved?: (s: Schedule) => void; onReload: () => void
}) {
  useT()
  const qc = useQueryClient()
  const [state, setState] = useState<FormState>(() => initForm(schedule))
  const [review, setReview] = useState(false)
  const nowMs = useNow(clockOffset, 30_000)
  const closed = status === 'CLOSED', open = status === 'OPEN'
  const lockedAll = closed
  const lockedMost = open || closed // OPEN: only endAt / rule can change
  const issues = useMemo(() => validateForm(state, schedule, status), [state, schedule, status])
  const changed = isChanged(state, schedule)
  const mine = viewerZone()
  const zones = useMemo(() => zoneOptions([state.tz, mine]), [state.tz, mine])
  const set = (patch: Partial<FormState>) => setState(s => ({ ...s, ...patch }))
  const anchor = state.start.ms ?? state.end.ms

  const save = useMutation({
    mutationFn: () => calendarApi.putSchedule(schedule.examId, toInput(state, schedule)),
    onSuccess: async s => {
      await Promise.all(['calendar', 'schedule', 'exam', 'exams'].map(k => qc.invalidateQueries({ queryKey: [k] })))
      onSaved?.(s); onClose()
    },
    onError: () => setReview(false),
  })
  const err = save.isError ? errorInfo(save.error) : null

  function timeField(label: string, key: 'start' | 'end', locked: boolean) {
    const f: Field = state[key], issue = fieldIssue(f, state.tz), id = `sch-${key}`
    return (
      <div className="stack-sm">
        <FormField label={label} htmlFor={id} error={issue?.kind === 'gap' ? t('Giờ này không tồn tại ở {0} do chuyển giờ mùa hè. Hãy chọn giờ khác.', { 0: state.tz }) : issue?.kind === 'invalid' ? t('Giờ chưa hợp lệ.') : undefined}
          hint={f.ms !== null && !issue ? `${zoneLabel(f.ms, state.tz)} · UTC ${new Date(f.ms).toISOString().slice(0, 16).replace('T', ' ')}` : t('Để trống nếu không giới hạn.')}>
          <div className="row" style={{ flexWrap: 'nowrap' }}>
            <input id={id} type="datetime-local" className="input" value={f.wall} disabled={locked || lockedAll} aria-invalid={!!issue}
              onChange={e => set({ [key]: setWall(e.target.value, state.tz) } as Partial<FormState>)} />
            <button type="button" className="btn btn-ghost btn-sm" disabled={locked || lockedAll || !f.wall} onClick={() => set({ [key]: setWall('', state.tz) } as Partial<FormState>)}>{t('Xóa')}</button>
          </div>
        </FormField>
        {issue?.kind === 'overlap' && (
          <fieldset className="stack-sm">
            <legend className="field-label">{t('Giờ này xuất hiện hai lần do chuyển giờ. Chọn lần nào?')}</legend>
            {issue.options.map((o, i) => (
              <label key={o.ms} className="choice">
                <input type="radio" name={`${id}-offset`} onChange={() => set({ [key]: chooseOption(f, o.ms, state.tz) } as Partial<FormState>)} />
                <span>{i === 0 ? t('Lần thứ nhất') : t('Lần thứ hai')}{t(': độ lệch')} {formatOffset(o.offsetMin)} <span className="muted">(UTC {new Date(o.ms).toISOString().slice(11, 16)})</span></span>
              </label>
            ))}
          </fieldset>
        )}
      </div>
    )
  }

  if (review) {
    const input = toInput(state, schedule)
    const show = (iso: string | null, tz: string) => (iso ? `${formatDateTimeIn(Date.parse(iso), tz)} ${zoneLabel(Date.parse(iso), tz)}` : t('Không giới hạn'))
    const rows: [string, string, string][] = [
      [t('Múi giờ'), schedule.timezone, input.timezone],
      [t('Bắt đầu'), show(schedule.startAt, schedule.timezone), show(input.startAt, input.timezone)],
      [t('Kết thúc'), show(schedule.endAt, schedule.timezone), show(input.endAt, input.timezone)],
      [t('Cách tính hạn nộp'), RULE_LABEL[schedule.deadlineRule], RULE_LABEL[input.deadlineRule]],
      [t('Tự động mở / đóng'), t('{0} / {1}', { 0: schedule.autoOpen ? 'mở' : '-', 1: schedule.autoClose ? 'đóng' : '-' }), t('{0} / {1}', { 0: input.autoOpen ? 'mở' : '-', 1: input.autoClose ? 'đóng' : '-' })],
      [t('Nhắc'), schedule.reminderOffsetsMinutes.length ? t('{0} mốc', { 0: schedule.reminderOffsetsMinutes.length }) : t('Tắt'), input.reminderOffsetsMinutes.length ? t('{0} mốc', { 0: input.reminderOffsetsMinutes.length }) : t('Tắt')],
    ]
    return (
      <div className="stack">
        <Notice tone="warning" title={t('Xác nhận lưu lịch')}>
          {t('Người được giao, đã tham gia hoặc đang theo dõi')}{status === 'DRAFT' ? t(' (sau khi xuất bản)') : ''} {t('sẽ nhận thông báo đổi lịch; các mốc nhắc cũ được lập lại theo giờ mới.')}
        </Notice>
        <div className="table-wrap card"><table className="table">
          <thead><tr><th scope="col">{t('Mục')}</th><th scope="col">{t('Hiện tại')}</th><th scope="col">{t('Sau khi lưu')}</th></tr></thead>
          <tbody>{rows.filter(([, a, b]) => a !== b).map(([k, a, b]) => <tr key={k}><td>{k}</td><td className="muted">{a}</td><td>{b}</td></tr>)}</tbody>
        </table></div>
        {input.reason && <p className="small"><span className="muted">{t('Lý do:')}</span> {input.reason}</p>}
        {err && <Notice tone="danger" live>{err.message}</Notice>}
        <div className="row" style={{ justifyContent: 'flex-end' }}>
          <button type="button" className="btn btn-secondary" disabled={save.isPending} onClick={() => setReview(false)}>{t('Quay lại sửa')}</button>
          <button type="button" className="btn btn-primary" disabled={save.isPending} aria-busy={save.isPending} onClick={() => save.mutate()}>{save.isPending ? t('Đang lưu…') : t('Xác nhận lưu')}</button>
        </div>
      </div>
    )
  }

  return (
    <form className="stack-lg" onSubmit={e => { e.preventDefault(); if (issues.length === 0 && changed) setReview(true) }} noValidate>
      {closed && <Notice tone="info" title={t('Kỳ thi đã đóng')}>{t('Không chỉnh được lịch của kỳ thi đã đóng. Dùng "Mở lại" ở trang đề thi (cần trước giờ kết thúc) nếu muốn tiếp tục.')}</Notice>}
      {open && <Notice tone="info" title={t('Kỳ thi đang mở')}>{t('Chỉ được kéo dài giờ kết thúc')}{schedule.endAt ? '' : t(' (đề này chưa có giờ kết thúc nên không thêm được)')}{t(', và đổi cách tính hạn nộp nếu chưa có thí sinh nào bắt đầu. Các phiên đã bắt đầu giữ hạn nộp cũ.')}</Notice>}
      {status === 'PUBLISHED' && <Notice tone="info">{t('Đã xuất bản: dời lịch được khi chưa có thí sinh bắt đầu, cần nhập lý do.')}</Notice>}

      <section className="stack" aria-label={t('Thời gian')}>
        <FormField label={t('Múi giờ của kỳ thi')} htmlFor="sch-tz" hint={anchor !== null ? t('Đổi múi giờ chỉ đổi cách hiển thị giờ, không đổi thời điểm đã đặt.') : t('Giờ của máy bạn đang là {0}.', { 0: mine })}>
          <Select id="sch-tz" className="input" value={state.tz} disabled={lockedMost} onChange={e => setState(s => changeZone(s, e.target.value))}>
            {zones.map(z => <option key={z} value={z}>{z}{z === mine ? t(' (máy bạn)') : ''}</option>)}
          </Select>
        </FormField>
        <div className="grid-2">
          {timeField(t('Bắt đầu'), 'start', lockedMost)}
          {timeField(t('Kết thúc'), 'end', false)}
        </div>
        <p className="xsmall muted">{t('Cửa sổ làm bài là [bắt đầu, kết thúc): từ giờ kết thúc không nhận lượt mới. Thời lượng làm bài')}{durationMinutes ? t(' {0} phút', { 0: durationMinutes }) : ''} {t('tính riêng từ lúc từng thí sinh bắt đầu.')}</p>
      </section>

      <fieldset className="stack-sm" disabled={lockedAll}>
        <legend className="field-label" style={{ marginBottom: 6 }}>{t('Cách tính hạn nộp bài')}</legend>
        {RULES.map(rule => (
          <label key={rule} className="choice">
            <input type="radio" name="sch-rule" checked={state.rule === rule} onChange={() => set({ rule })} />
            <span><strong>{RULE_LABEL[rule]}</strong><br /><span className="xsmall muted">{RULE_HELP[rule]}</span>
              {rule !== 'FULL_DURATION' && state.end.ms === null && <><br /><span className="xsmall" style={{ color: 'var(--color-danger)' }}>{t('Cần đặt giờ kết thúc.')}</span></>}</span>
          </label>
        ))}
      </fieldset>

      <fieldset className="stack-sm" disabled={lockedMost}>
        <legend className="field-label" style={{ marginBottom: 6 }}>{t('Mở / đóng tự động')}</legend>
        <label className="check"><input type="checkbox" checked={state.autoOpen && state.start.ms !== null} disabled={state.start.ms === null} onChange={e => set({ autoOpen: e.target.checked })} />
          <span>{t('Tự động mở khi đến giờ bắt đầu')}<br /><span className="xsmall muted">{state.start.ms === null ? t('Cần giờ bắt đầu.') : t('Chỉ mở được khi đề đã xuất bản. Đóng thủ công sẽ không bị tự mở lại.')}</span></span></label>
        <label className="check"><input type="checkbox" checked={state.autoClose && state.end.ms !== null} disabled={state.end.ms === null} onChange={e => set({ autoClose: e.target.checked })} />
          <span>{t('Tự động đóng khi đến giờ kết thúc')}<br /><span className="xsmall muted">{state.end.ms === null ? t('Cần giờ kết thúc.') : t('Nếu tắt, đề vẫn mở nhưng không nhận lượt mới từ giờ kết thúc.')}</span></span></label>
      </fieldset>

      <ReminderEditor value={state.reminders} off={state.remindersOff} startMs={state.start.ms} tz={state.tz} nowMs={nowMs} disabled={lockedMost}
        onChange={(reminders, remindersOff) => set({ reminders, remindersOff })} />

      {status !== 'DRAFT' && !closed && (
        <FormField label={t('Lý do thay đổi')} htmlFor="sch-reason" required hint={t('Ghi vào nhật ký đề thi. Bắt buộc khi đổi lịch sau khi đã xuất bản.')}>
          <textarea id="sch-reason" className="input" rows={2} maxLength={500} value={state.reason} onChange={e => set({ reason: e.target.value })} />
        </FormField>
      )}

      {err && (
        <Notice tone="danger" live action={
          err.detail === 'SCHEDULE_REVISION_MISMATCH' ? <button type="button" className="btn btn-secondary btn-sm" onClick={onReload}>{t('Tải lại lịch mới nhất')}</button>
            : err.detail === 'START_ADMISSION_IN_PROGRESS' ? <button type="button" className="btn btn-secondary btn-sm" onClick={() => setReview(true)}>{t('Thử lại')}</button> : undefined}>
          {err.message}
        </Notice>
      )}
      {changed && issues.length > 0 && <Notice tone="warning" title={t('Cần chỉnh trước khi lưu')}><ul style={{ margin: '4px 0 0', paddingLeft: 18 }}>{issues.map(m => <li key={m}>{m}</li>)}</ul></Notice>}
      {!changed && !lockedAll && <p className="small muted">{t('Chưa có thay đổi nào.')}</p>}
      <div className="row" style={{ justifyContent: 'flex-end' }}>
        <button type="button" className="btn btn-secondary" onClick={onClose}>{lockedAll ? t('Đóng') : t('Hủy')}</button>
        {!lockedAll && <button type="submit" className="btn btn-primary" disabled={!changed || issues.length > 0}>{t('Xem lại và lưu')}</button>}
      </div>
      <p className="xsmall muted">{t('Phiên bản lịch hiện tại:')} {schedule.scheduleRevision}.</p>
    </form>
  )
}

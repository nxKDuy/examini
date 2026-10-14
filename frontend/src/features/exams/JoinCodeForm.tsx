import { useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Eye, KeyRound } from 'lucide-react'
import { examApi } from './examApi'
import { calendarApi, type Availability } from '../calendar/calendarApi'
import { reasonText, ruleLabel, windowText } from '../calendar/calendarModel'
import { StateBadge } from '../calendar/ExamCard'
import { useViewerZone } from '../calendar/useCalendar'
import { Notice } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { t, useT } from '../../lib/i18n'

export default function JoinCodeForm({ compact }: { compact?: boolean }) {
  useT()
  const navigate = useNavigate()
  const qc = useQueryClient()
  const viewerTz = useViewerZone()
  const [code, setCode] = useState('')
  const [error, setError] = useState('')
  const [preview, setPreview] = useState<Availability | null>(null)

  const join = useMutation({
    mutationFn: () => examApi.joinCode(code.trim()),
    onSuccess: async exam => { await Promise.all(['my-exams', 'calendar', 'availability'].map(k => qc.invalidateQueries({ queryKey: [k] }))); navigate(`/exam/lobby/${exam.id}`) },
    onError: e => setError(errorText(e)),
  })
  // Preview with a valid code: schedule and eligibility only. It never joins, takes capacity, or sends reminders.
  const look = useMutation({
    mutationFn: () => calendarApi.codePreview(code.trim()),
    onSuccess: setPreview,
    onError: e => { setPreview(null); setError(errorText(e)) },
  })
  const busy = join.isPending || look.isPending
  // An authoritative preview that says canJoin=false locks Join with the backend's reason; the backend still checks again on Join.
  const blocked = !!preview && !preview.canJoin
  const id = compact ? 'join-code-compact' : 'join-code'
  const w = preview ? windowText(preview, viewerTz) : null

  function submit(e: FormEvent) { e.preventDefault(); if (!code.trim() || blocked) return; setError(''); join.mutate() }
  function onPreview() { if (!code.trim()) return; setError(''); setPreview(null); look.mutate() }

  return (
    <form onSubmit={submit} className="stack-sm" aria-label={t('Tham gia bằng mã')}>
      <label htmlFor={id} className="field-label">{t('Mã tham gia')}</label>
      <div className="row" style={{ flexWrap: 'nowrap' }}>
        <input id={id} className="input" value={code} maxLength={50} autoComplete="off" spellCheck={false}
          onChange={e => { setCode(e.target.value); setPreview(null) }} aria-invalid={!!error} aria-describedby={error ? 'join-code-error' : undefined} style={{ flex: 1 }} />
        <button type="button" className="btn btn-secondary" disabled={busy || !code.trim()} onClick={onPreview}><Eye size={16} aria-hidden /> {look.isPending ? t('Đang xem…') : t('Xem trước')}</button>
        <button type="submit" className="btn btn-primary" disabled={busy || !code.trim() || blocked} aria-describedby={blocked ? 'join-code-blocked' : undefined}><KeyRound size={16} aria-hidden /> {join.isPending ? t('Đang kiểm tra…') : 'Tham gia'}</button>
      </div>
      {error ? <span id="join-code-error" className="field-error" role="alert">{error}</span>
        : <span className="field-hint">{t('Nhập mã giáo viên cung cấp. "Xem trước" chỉ hiện lịch và điều kiện, không tham gia hay giữ chỗ.')}</span>}
      {preview && w && (
        <Notice tone="info" title={preview.examTitle} live>
          <div className="stack-sm">
            <span className="row"><StateBadge state={preview.calendarState} /></span>
            <span>{w.primary}{w.zone && <> · {w.zone}</>}</span>
            {w.mine && <span>{w.mine}</span>}
            <span>{preview.durationMinutes} {t('phút ·')} {ruleLabel(preview.deadlineRule)}</span>
            {blocked && <span id="join-code-blocked"><strong>{t('Chưa tham gia được:')}</strong> {reasonText(preview.reasonCode) ?? t('Chưa tham gia được lúc này.')}</span>}
          </div>
        </Notice>
      )}
    </form>
  )
}

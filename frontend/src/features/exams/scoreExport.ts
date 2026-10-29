import type { CandidateResult, AnalyticsStatus } from './analyticsApi'
import { analyticsStatusLabel } from './analyticsModel'
import type { NameMap } from '../participants/useParticipantNames'
import { APP_ZONE } from '../../lib/zone'
import { t } from '../../lib/i18n'

/** A cell as Excel reads it: quoted when needed, and never starting a formula (names are user input). */
export function csvCell(value: unknown): string {
  let s = value === null || value === undefined ? '' : String(value)
  if (/^[=+\-@\t\r]/.test(s)) s = `'${s}`
  return /[",;\n\r]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s
}

/** dd/MM/yyyy HH:mm in Vietnam time: one cell Excel recognises as a date. */
const DATE = new Intl.DateTimeFormat('en-GB', { timeZone: APP_ZONE, day: '2-digit', month: '2-digit', year: 'numeric', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
export const csvDate = (iso: string | null | undefined) => (iso ? DATE.format(new Date(iso)).replace(',', '') : '')

const num = (n: number | null | undefined, digits = 2) => (n === null || n === undefined ? '' : String(Math.round(n * 10 ** digits) / 10 ** digits))

/** UTF-8 CSV with BOM (Excel shows Vietnamese correctly), one row per attempt, sorted by name then attempt. */
export function scoreCsv(rows: CandidateResult[], names: NameMap | undefined): string {
  const header = [t('STT'), t('Họ tên'), 'Email', t('Mã thí sinh'), t('Lượt'), t('Trạng thái'), t('Điểm'), t('Điểm tối đa'), t('Tỷ lệ (%)'), t('Nộp lúc'), t('Chấm lúc'), t('Ghi chú')]
  const sorted = [...rows].sort((a, b) => {
    const na = names?.get(a.candidateId)?.name ?? a.candidateId, nb = names?.get(b.candidateId)?.name ?? b.candidateId
    return na.localeCompare(nb, 'vi') || (a.attemptNumber ?? 0) - (b.attemptNumber ?? 0)
  })
  const lines = sorted.map((r, i) => {
    const p = names?.get(r.candidateId)
    const percent = r.score !== null && r.maxScore > 0 ? (r.score * 100) / r.maxScore : null
    return [i + 1, p?.name ?? '', p?.email ?? '', r.candidateId, r.attemptNumber ?? '', analyticsStatusLabel[r.status as AnalyticsStatus] ?? r.status,
      num(r.score), num(r.maxScore), num(percent, 1), csvDate(r.submittedAt), csvDate(r.gradedAt), r.invalidated ? t('Lượt bị vô hiệu hóa') : '']
  })
  return '﻿' + [header, ...lines].map(row => row.map(csvCell).join(',')).join('\r\n') + '\r\n'
}

export function download(filename: string, text: string, type = 'text/csv;charset=utf-8') {
  const url = URL.createObjectURL(new Blob([text], { type }))
  const a = document.createElement('a')
  a.href = url; a.download = filename; document.body.appendChild(a); a.click(); a.remove()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

/** File-name-safe slug of the exam title (keeps Vietnamese letters readable as ASCII). */
export const fileSlug = (title: string) => title.normalize('NFD').replace(/[̀-ͯ]/g, '').replace(/đ/g, 'd').replace(/Đ/g, 'D')
  .replace(/[^A-Za-z0-9]+/g, '-').replace(/^-|-$/g, '').slice(0, 60) || 'ky-thi'

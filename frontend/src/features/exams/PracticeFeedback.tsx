import { CheckCircle2, CircleAlert, XCircle } from 'lucide-react'
import { t as tt, useT } from '../../lib/i18n'
import type { PracticeCheck } from './examApi'
import { verdictOf } from './practiceModel'

export default function PracticeFeedback({ check }: { check: PracticeCheck }) {
  useT()
  const v = verdictOf(check)
  const Icon = v === 'right' ? CheckCircle2 : v === 'partial' ? CircleAlert : XCircle
  return (
    <div className="practice-feedback" data-verdict={v} role="status">
      <p className="row" style={{ gap: 8 }}><Icon size={18} aria-hidden />
        <strong>{v === 'right' ? tt('Chính xác!') : v === 'partial' ? tt('Đúng một phần ({0}% điểm câu)', { 0: Math.round(check.share * 100) }) : tt('Chưa đúng')}</strong></p>
      {!check.correct && check.correctAnswers.length > 0 && <div className="small">
        <span className="xsmall muted">{tt('Đáp án đúng')}</span>
        <p className="prewrap">{check.correctAnswers.join('\n')}</p></div>}
      {check.explanation?.trim() && <div className="small"><span className="xsmall muted">{tt('Giải thích')}</span><p className="prewrap">{check.explanation}</p></div>}
    </div>
  )
}

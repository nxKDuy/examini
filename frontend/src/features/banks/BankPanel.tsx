import { useRef } from 'react'
import { X } from 'lucide-react'
import './banks.css'
import { useModalFocus } from '../../components/useModalFocus'
import type { QuestionOption } from '../question-bank/questionApi'
import { Badge } from '../../components/ui'
import { DIFFICULTY_LABEL, QUESTION_TYPE_LABEL } from '../../lib/format'
import { t, useT } from '../../lib/i18n'

type Opt = Pick<QuestionOption, 'id' | 'content' | 'displayOrder'> & { correct?: boolean }
export interface BodyQuestion { content: string; questionType: string; difficultyLevel: string; options: Opt[]; questionVersion: number; expectedAnswer?: string | null; categoryName?: string }

/** Question text and options. `correct` only exists when the server sent an answer key (reviewers); a safe question has none and none is invented. */
export function QuestionView({ question, showAnswers = false }: { question: BodyQuestion; showAnswers?: boolean }) {
  useT()
  return (
    <div className="stack">
      <div className="row" style={{ gap: 6 }}>
        <Badge>{QUESTION_TYPE_LABEL[question.questionType] ?? question.questionType}</Badge><Badge>{DIFFICULTY_LABEL[question.difficultyLevel] ?? question.difficultyLevel}</Badge>
        <Badge tone="muted">{t('Phiên bản')} {question.questionVersion}</Badge>
      </div>
      {question.categoryName && <p className="xsmall muted">{question.categoryName}</p>}
      <p className="question-text prewrap">{question.content}</p>
      {question.options.length > 0 && (
        <ol className="stack-sm" style={{ listStyle: 'none', padding: 0, margin: 0 }}>
          {[...question.options].sort((a, b) => a.displayOrder - b.displayOrder).map((o, i) => (
            <li key={o.id} className="row" style={{ flexWrap: 'nowrap', alignItems: 'flex-start', padding: '8px 10px', background: showAnswers && o.correct ? 'var(--color-success-soft)' : 'var(--color-subtle)' }}>
              <span className="option-key" aria-hidden>{String.fromCharCode(65 + i)}</span>
              <span className="prewrap" style={{ flex: 1 }}>{o.content}</span>
              {showAnswers && o.correct === true && <Badge tone="success">{t('Đáp án đúng')}</Badge>}
            </li>
          ))}
        </ol>
      )}
      {showAnswers && question.expectedAnswer && <div className="small"><span className="xsmall muted">{t('Đáp án mẫu')}</span><p className="prewrap">{question.expectedAnswer}</p></div>}
    </div>
  )
}

/** Right drawer on desktop, bottom sheet (focus trap, backdrop) on narrow screens. */
export default function BankPanel({ title, subtitle, narrow, onClose, children }: { title: string; subtitle?: string; narrow: boolean; onClose: () => void; children: React.ReactNode }) {
  useT()
  const ref = useRef<HTMLElement>(null)
  const headingRef = useRef<HTMLHeadingElement>(null)
  useModalFocus(ref, { trap: narrow, escape: true, onEscape: onClose, initialFocus: () => headingRef.current })
  return (
    <>
      {narrow && <button type="button" className="bk-sheet-backdrop" aria-label={t('Đóng chi tiết')} onClick={onClose} tabIndex={-1} />}
      <aside ref={ref} className="bk-panel" role={narrow ? 'dialog' : 'complementary'} aria-modal={narrow || undefined} aria-label={title}>
        <div className="bk-panel-head">
          <div style={{ minWidth: 0 }}>
            <h2 ref={headingRef} tabIndex={-1} style={{ fontSize: '1.15rem', overflowWrap: 'anywhere' }}>{title}</h2>
            {subtitle && <p className="xsmall muted">{subtitle}</p>}
          </div>
          <button type="button" className="btn btn-ghost btn-icon" aria-label={t('Đóng')} onClick={onClose}><X size={18} /></button>
        </div>
        <div className="bk-panel-body">{children}</div>
      </aside>
    </>
  )
}

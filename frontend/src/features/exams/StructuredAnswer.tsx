import { Fragment } from 'react'
import Select from '../../components/Select'
import { t as tt, useT } from '../../lib/i18n'
import { filledCount, parseTexts, setText, splitBlanks } from '../question-bank/structuredModel'

/** Per-gap / per-pair state shown after checking (practice mode); absent while answering. */
export type PartState = 'right' | 'wrong' | undefined

/**
 * FILL_BLANK: the stem with an input in each ___ gap (gap i answers option i, in display order).
 * Typing is saved like a short answer: debounced, and flushed on blur.
 */
export function ClozeText({ content, gapIds, value, disabled, onChange, onBlur, states }: {
  content: string; gapIds: string[]; value: string; disabled: boolean
  onChange: (v: string, immediate: boolean) => void; onBlur?: () => void; states?: Record<string, PartState>
}) {
  useT()
  const pieces = splitBlanks(content)
  const typed = parseTexts(value)
  return (
    <div className="stack-sm">
      <p className="question-text cloze-text">
        {pieces.map((piece, i) => (
          <Fragment key={i}>
            <span className="prewrap">{piece}</span>
            {i < pieces.length - 1 && gapIds[i] && (
              <span className="cloze-gap" data-state={states?.[gapIds[i]]}>
                <span className="gap-no" aria-hidden>({i + 1})</span>
                <input aria-label={tt('Chỗ trống {0}', { 0: i + 1 })} value={typed[gapIds[i]] ?? ''} disabled={disabled} maxLength={500} autoComplete="off" spellCheck={false}
                  onChange={e => onChange(setText(value, gapIds[i], e.target.value), false)} onBlur={onBlur} />
              </span>
            )}
          </Fragment>
        ))}
      </p>
      <span className="xsmall muted">{tt('Đã điền {0}/{1} chỗ trống', { 0: filledCount(value, gapIds), 1: gapIds.length })}</span>
    </div>
  )
}

/** MATCHING: each left item picks one right item from the shared (alphabetical) list. */
export function MatchingAnswer({ items, targets, value, disabled, onChange, states }: {
  items: { id: string; content: string }[]; targets: string[]; value: string; disabled: boolean
  onChange: (v: string, immediate: boolean) => void; states?: Record<string, PartState>
}) {
  useT()
  const chosen = parseTexts(value)
  return (
    <fieldset className="stack-sm">
      <legend className="small" style={{ marginBottom: 4 }}><strong>{tt('Nối mỗi mục ở cột trái với một mục ở cột phải')}</strong>{' '}
        <span className="muted">· {tt('đã nối {0}/{1}', { 0: filledCount(value, items.map(i => i.id)), 1: items.length })}</span></legend>
      {items.map((item, i) => (
        <div key={item.id} className="match-row" data-chosen={!!chosen[item.id]} data-state={states?.[item.id]}>
          <span className="option-key" aria-hidden>{i + 1}</span>
          <span className="prewrap" id={`match-${item.id}`}>{item.content}</span>
          <Select aria-label={tt('Vế phải cho mục {0}', { 0: i + 1 })} className="input" value={chosen[item.id] ?? ''} disabled={disabled}
            onChange={e => onChange(setText(value, item.id, e.target.value), true)}>
            <option value="">{tt('— Chọn —')}</option>
            {targets.map(t => <option key={t} value={t}>{t}</option>)}
          </Select>
        </div>
      ))}
      {Object.keys(chosen).length > 0 && !disabled && <div><button type="button" className="btn btn-ghost btn-sm" onClick={() => onChange('', true)}>{tt('Xóa lựa chọn')}</button></div>}
    </fieldset>
  )
}

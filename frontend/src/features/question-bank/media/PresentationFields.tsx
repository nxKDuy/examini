import { useRef, useState } from 'react'
import { useMutation, useQuery } from '@tanstack/react-query'
import { BookOpen, Headphones, Image as ImageIcon, Paperclip, Trash2, X } from 'lucide-react'
import { questionBankApi, type MediaRef, type Presentation, type QuestionType } from '../questionApi'
import { MEDIA_ACCEPT, mediaProblem } from './mediaModel'
import { MediaAudio, MediaImage } from './QuestionMedia'
import { Field } from '../../../components/ui'
import Select from '../../../components/Select'
import { errorText } from '../../../lib/errors'
import { t, useT } from '../../../lib/i18n'

const PROBLEM: Record<string, () => string> = {
  TYPE: () => t('Chỉ nhận ảnh (PNG, JPG, WEBP, GIF) hoặc âm thanh (MP3, M4A, OGG, WAV, WEBM).'),
  IMAGE_SIZE: () => t('Ảnh tối đa 3 MB.'),
  AUDIO_SIZE: () => t('Âm thanh tối đa 20 MB.'),
}

/** Upload button that returns the stored media reference. */
export function MediaUpload({ onUploaded, accept = MEDIA_ACCEPT, label, compact }: { onUploaded: (m: MediaRef) => void; accept?: string; label: string; compact?: boolean }) {
  useT()
  const input = useRef<HTMLInputElement>(null)
  const [error, setError] = useState('')
  const up = useMutation({
    mutationFn: (f: File) => questionBankApi.uploadMedia(f, f.name),
    onSuccess: m => { setError(''); onUploaded({ id: m.id, kind: m.kind, name: m.name }) },
    onError: e => setError(errorText(e)),
  })
  return (
    <span className="stack-sm" style={{ gap: 4 }}>
      <input ref={input} type="file" accept={accept} hidden onChange={e => {
        const f = e.target.files?.[0]; e.target.value = ''
        if (!f) return
        const p = mediaProblem(f)
        if (p) { setError(PROBLEM[p]()); return }
        up.mutate(f)
      }} />
      <button type="button" className={compact ? 'btn btn-ghost btn-sm' : 'btn btn-secondary btn-sm'} disabled={up.isPending} aria-busy={up.isPending} onClick={() => input.current?.click()}>
        <Paperclip size={14} aria-hidden /> {up.isPending ? t('Đang tải lên…') : label}</button>
      {error && <span className="field-error" role="alert">{error}</span>}
    </span>
  )
}

function MediaChips({ media, onRemove }: { media: MediaRef[]; onRemove: (id: string) => void }) {
  useT()
  if (!media.length) return null
  return (
    <div className="stack-sm">
      {media.map(m => (
        <div key={m.id} className="stack-sm" style={{ gap: 4 }}>
          <span className="media-chip">
            {m.kind === 'IMAGE' ? <ImageIcon size={14} aria-hidden /> : <Headphones size={14} aria-hidden />}
            <span>{m.name || (m.kind === 'IMAGE' ? t('Hình ảnh') : t('Âm thanh'))}</span>
            <button type="button" className="btn btn-ghost btn-icon" style={{ minHeight: 24, minWidth: 24, padding: 0 }} aria-label={t('Gỡ {0}', { 0: m.name || '' })} onClick={() => onRemove(m.id)}><X size={14} /></button>
          </span>
          {m.kind === 'IMAGE' ? <MediaImage id={m.id} alt={m.name || t('Hình ảnh')} maxHeight={160} /> : <MediaAudio id={m.id} name={m.name} />}
        </div>
      ))}
    </div>
  )
}

const newKey = () => (typeof globalThis.crypto?.randomUUID === 'function' ? crypto.randomUUID()
  : 'xxxxxxxx-xxxx-4xxx-8xxx-xxxxxxxxxxxx'.replace(/x/g, () => Math.floor(Math.random() * 16).toString(16)))

/**
 * Media, shared passage and per-type limits of a question. The passage key groups questions: questions with the same
 * key stay together and the passage is shown once above them.
 */
export default function PresentationFields({ value, onChange, type }: { value: Presentation; onChange: (p: Presentation) => void; type: QuestionType }) {
  useT()
  const media = value.media ?? []
  const passage = value.passage ?? null
  const [pickPassage, setPickPassage] = useState(false)
  const passages = useQuery({ queryKey: ['question-passages'], queryFn: questionBankApi.passages, enabled: pickPassage, staleTime: 60_000 })
  const hasAudio = media.some(m => m.kind === 'AUDIO') || (passage?.media ?? []).some(m => m.kind === 'AUDIO')
  const set = (p: Partial<Presentation>) => onChange({ ...value, ...p })
  const num = (s: string) => (s.trim() === '' ? null : Math.max(0, Math.floor(Number(s) || 0)))

  return (
    <fieldset className="stack" style={{ border: '1px solid var(--color-hairline)', padding: 14 }}>
      <legend className="field-label" style={{ padding: '0 6px' }}>{t('Hình ảnh, âm thanh và bài đọc (tùy chọn)')}</legend>
      <div className="stack-sm">
        <span className="small">{t('Đính kèm cho câu hỏi: hình minh họa, sơ đồ hoặc đoạn nghe.')}</span>
        <MediaChips media={media} onRemove={id => set({ media: media.filter(m => m.id !== id) })} />
        {media.length < 6 && <div><MediaUpload label={t('Thêm ảnh / âm thanh')} onUploaded={m => set({ media: [...media, m] })} /></div>}
      </div>

      {!passage ? (
        <div className="row" style={{ gap: 8, flexWrap: 'wrap' }}>
          <button type="button" className="btn btn-secondary btn-sm" onClick={() => set({ passage: { key: newKey(), title: '', content: '', media: [] } })}>
            <BookOpen size={14} aria-hidden /> {t('Thêm bài đọc / đoạn nghe dùng chung')}</button>
          <button type="button" className="btn btn-ghost btn-sm" aria-expanded={pickPassage} onClick={() => setPickPassage(v => !v)}>{t('Dùng lại bài đọc đã có')}</button>
        </div>
      ) : (
        <div className="passage">
          <div className="spread"><strong className="small">{t('Bài đọc dùng chung')}</strong>
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => set({ passage: null })}><Trash2 size={14} aria-hidden /> {t('Bỏ bài đọc')}</button></div>
          <p className="field-hint">{t('Các câu dùng cùng bài đọc luôn được xếp liền nhau (cả khi trộn câu) và bài đọc hiện kèm từng câu. Sửa bài đọc ở đây chỉ áp dụng cho câu này.')}</p>
          <Field label={t('Tiêu đề bài đọc')} htmlFor="q-passage-title">
            <input id="q-passage-title" className="input" maxLength={200} value={passage.title ?? ''} onChange={e => set({ passage: { ...passage, title: e.target.value } })} />
          </Field>
          <Field label={t('Nội dung bài đọc')} htmlFor="q-passage" hint={t('{0}/20000 ký tự', { 0: passage.content.length })}>
            <textarea id="q-passage" className="input" rows={6} maxLength={20000} value={passage.content} onChange={e => set({ passage: { ...passage, content: e.target.value } })} />
          </Field>
          <MediaChips media={passage.media ?? []} onRemove={id => set({ passage: { ...passage, media: (passage.media ?? []).filter(m => m.id !== id) } })} />
          {(passage.media ?? []).length < 5 && <div><MediaUpload label={t('Thêm ảnh / âm thanh cho bài đọc')} onUploaded={m => set({ passage: { ...passage, media: [...(passage.media ?? []), m] } })} /></div>}
        </div>
      )}
      {pickPassage && !passage && (
        <div className="stack-sm">
          {passages.isLoading ? <span className="small muted">{t('Đang tải…')}</span>
            : passages.isError ? <span className="field-error">{errorText(passages.error)}</span>
            : !passages.data?.length ? <span className="small muted">{t('Bạn chưa có bài đọc nào.')}</span>
            : <Field label={t('Chọn bài đọc')} htmlFor="q-passage-pick">
                <Select id="q-passage-pick" className="input" value="" onChange={e => {
                  const p = passages.data?.find(x => x.key === e.target.value)
                  if (p) { set({ passage: { ...p, media: p.media ?? [] } }); setPickPassage(false) }
                }}>
                  <option value="">{t('— Chọn —')}</option>
                  {passages.data.map(p => <option key={p.key} value={p.key}>{(p.title || p.content).slice(0, 80)}</option>)}
                </Select>
              </Field>}
        </div>
      )}

      <div className="grid-2">
        {hasAudio && <Field label={t('Số lần được nghe')} htmlFor="q-plays" hint={t('Để trống: nghe không giới hạn. Tối đa 10.')}>
          <input id="q-plays" className="input" type="number" min={1} max={10} value={value.maxPlays ?? ''} onChange={e => set({ maxPlays: num(e.target.value) })} />
        </Field>}
        {type === 'ESSAY' && <>
          <Field label={t('Số từ tối thiểu')} htmlFor="q-minw"><input id="q-minw" className="input" type="number" min={0} max={5000} value={value.minWords ?? ''} onChange={e => set({ minWords: num(e.target.value) })} /></Field>
          <Field label={t('Số từ tối đa')} htmlFor="q-maxw"><input id="q-maxw" className="input" type="number" min={0} max={5000} value={value.maxWords ?? ''} onChange={e => set({ maxWords: num(e.target.value) })} /></Field>
        </>}
        {type === 'SPEAKING' && <Field label={t('Thời lượng ghi âm tối đa (giây)')} htmlFor="q-rec" hint={t('Từ 10 đến 600 giây. Mặc định 120.')}>
          <input id="q-rec" className="input" type="number" min={10} max={600} value={value.maxRecordSeconds ?? 120} onChange={e => set({ maxRecordSeconds: num(e.target.value) })} />
        </Field>}
      </div>
    </fieldset>
  )
}

/** Image attached to one multiple-choice option (keyed by its position). */
export function OptionImage({ id, onSet, onClear, label }: { id?: string; onSet: (id: string) => void; onClear: () => void; label: string }) {
  useT()
  if (id) return (
    <span className="row" style={{ gap: 4, flexWrap: 'nowrap' }}>
      <MediaImage id={id} alt={label} maxHeight={48} />
      <button type="button" className="btn btn-ghost btn-icon" aria-label={t('Gỡ ảnh {0}', { 0: label })} onClick={onClear}><X size={14} /></button>
    </span>
  )
  return <MediaUpload compact accept="image/png,image/jpeg,image/webp,image/gif" label={t('Ảnh')} onUploaded={m => m.kind === 'IMAGE' && onSet(m.id)} />
}

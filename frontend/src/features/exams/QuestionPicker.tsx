import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import type { Question } from '../question-bank/questionApi'
import { bankApi, type EligibleFilters, type SetEligibleItems } from '../banks/bankApi'
import { BANK_TYPE_LABEL } from '../banks/bankModel'
import SubjectCategoryFields from '../question-bank/SubjectCategoryFields'
import { MAX_EXAM_QUESTIONS, SKIP_REASON, addToSelection, chunk } from './builderModel'
import { useAuthStore } from '../../store/authStore'
import { Dialog } from '../../components/Dialog'
import { EmptyState, ErrorState, LoadingState, Notice, Pagination } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { DIFFICULTY_LABEL, QUESTION_TYPE_LABEL } from '../../lib/format'
import { t, useT } from '../../lib/i18n'
import Select from '../../components/Select'

type Mode = 'filter' | 'set'
type Flash = { tone: 'info' | 'warning' | 'danger'; text: string } | null

/**
 * Select provider-eligible resources (publication IDs preserve immutable snapshots). The selection is kept
 * across pages, filters and the two sources, ordered, and never holds the same question twice.
 */
export default function QuestionPicker({ excluded, onAdd, onClose }: { excluded: string[]; onAdd: (qs: Question[]) => void; onClose: () => void }) {
  useT()
  const [mode, setMode] = useState<Mode>('filter')
  const [order, setOrder] = useState<string[]>([])
  const [known, setKnown] = useState<Map<string, Question>>(new Map())
  const [flash, setFlash] = useState<Flash>(null)
  const [busy, setBusy] = useState(false)
  const room = Math.max(0, MAX_EXAM_QUESTIONS - excluded.length - order.length)

  function add(qs: Question[]) {
    setKnown(prev => { const next = new Map(prev); qs.forEach(q => next.set(q.id, q)); return next })
    const r = addToSelection(order, qs.map(q => q.id), excluded)
    setOrder(r.next)
    return r
  }
  const remove = (ids: string[]) => setOrder(prev => prev.filter(id => !ids.includes(id)))
  const toggle = (q: Question) => { if (order.includes(q.id)) remove([q.id]); else { const r = add([q]); if (r.overflow) setFlash({ tone: 'warning', text: t('Đề thi tối đa {max} câu.', { max: MAX_EXAM_QUESTIONS }) }) } }

  function report(r: ReturnType<typeof addToSelection>, extra = '') {
    const parts = [t('Đã thêm {n} câu vào lựa chọn.', { n: r.added })]
    if (r.duplicates) parts.push(t('{n} câu đã có trong đề hoặc đã chọn nên bỏ qua.', { n: r.duplicates }))
    if (r.overflow) parts.push(t('{n} câu không thêm vì đề thi tối đa {max} câu.', { n: r.overflow, max: MAX_EXAM_QUESTIONS }))
    if (extra) parts.push(extra)
    setFlash({ tone: r.overflow || extra ? 'warning' : 'info', text: parts.join(' ') })
  }

  const selected = order.map(id => known.get(id)).filter((q): q is Question => !!q)

  return (
    <Dialog size="lg" title={t('Thêm câu hỏi vào đề')} onClose={onClose} busy={busy}
      description={t('Chỉ hiện câu đủ điều kiện dùng cho đề. Máy chủ kiểm tra lại quyền và điều kiện khi lưu và khi xuất bản.')}
      footer={<>
        <span className="small muted" style={{ marginRight: 'auto' }} role="status">{t('Đã chọn {n} câu · còn chỗ {room}', { n: order.length, room })}</span>
        <button type="button" className="btn btn-secondary" onClick={onClose} disabled={busy}>{t('Hủy')}</button>
        <button type="button" className="btn btn-primary" disabled={order.length === 0 || busy} onClick={() => onAdd(selected)}>{t('Thêm {n} câu hỏi', { n: order.length || '' })}</button>
      </>}>
      <div className="stack">
        <div className="tabs" role="tablist" aria-label={t('Nguồn câu hỏi')}>
          <button type="button" role="tab" className="tab" aria-selected={mode === 'filter'} onClick={() => setMode('filter')}>{t('Lọc câu hỏi')}</button>
          <button type="button" role="tab" className="tab" aria-selected={mode === 'set'} onClick={() => setMode('set')}>{t('Từ bộ câu hỏi')}</button>
          <span className="small muted" style={{ marginLeft: 'auto', alignSelf: 'center', paddingRight: 8 }}>{t('Lựa chọn giữ nguyên khi đổi trang, bộ lọc hoặc nguồn')}</span>
        </div>
        {flash && <Notice tone={flash.tone} live>{flash.text}</Notice>}
        {mode === 'filter'
          ? <FilterSource excluded={excluded} order={order} busy={busy} setBusy={setBusy} onToggle={toggle} onAdd={add} onReport={report} onRemove={remove} onError={text => setFlash({ tone: 'danger', text })} />
          : <SetSource excluded={excluded} order={order} onAdd={add} onReport={report} />}
        {order.length > 0 && <details>
          <summary className="field-label" style={{ cursor: 'pointer' }}>{t('Xem {n} câu đã chọn (chưa thêm vào đề)', { n: order.length })}</summary>
          <ol className="stack-sm" style={{ margin: '10px 0 0', paddingLeft: 22 }}>
            {selected.map(q => <li key={q.id} className="small"><span className="truncate-2">{q.content}</span> <button type="button" className="btn btn-ghost btn-sm" onClick={() => remove([q.id])}>{t('Bỏ chọn')}</button></li>)}
          </ol>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => setOrder([])}>{t('Bỏ chọn tất cả')}</button>
        </details>}
      </div>
    </Dialog>
  )
}

function FilterSource({ excluded, order, busy, setBusy, onToggle, onAdd, onReport, onRemove, onError }: {
  excluded: string[]; order: string[]; busy: boolean; setBusy: (b: boolean) => void; onToggle: (q: Question) => void
  onAdd: (qs: Question[]) => ReturnType<typeof addToSelection>; onReport: (r: ReturnType<typeof addToSelection>, extra?: string) => void
  onRemove: (ids: string[]) => void; onError: (text: string) => void
}) {
  const [subjectId, setSubjectId] = useState('')
  const [f, setF] = useState<EligibleFilters>({})
  const [text, setText] = useState('')
  const [page, setPage] = useState(0)
  const banks = useQuery({ queryKey: ['banks', 'picker'], queryFn: bankApi.allBanks })
  const list = useQuery({ queryKey: ['questions', 'eligible', f, page], queryFn: () => bankApi.eligible({ ...f, page, size: 10 }), retry: false, placeholderData: prev => prev })
  const set = (patch: EligibleFilters) => { setF(prev => ({ ...prev, ...patch })); setPage(0) }
  const rows = list.data?.content ?? []
  const pageFree = rows.filter(q => !excluded.includes(q.id) && !order.includes(q.id))

  async function selectAll() {
    setBusy(true)
    try {
      const res = await bankApi.eligibleIds(f)
      const wanted = res.ids.filter(id => !excluded.includes(id) && !order.includes(id))
      const details = (await Promise.all(chunk(wanted).map(ids => bankApi.eligibleBatch(ids)))).flat().filter(q => q.eligible !== false)
      const byId = new Map(details.map(q => [q.id, q]))
      const r = onAdd(wanted.map(id => byId.get(id)).filter((q): q is Question => !!q))
      onReport({ ...r, duplicates: r.duplicates + (res.ids.length - wanted.length) }, res.truncated ? t('Bộ lọc khớp {n} câu; hệ thống chỉ chọn tối đa 500 câu đầu.', { n: res.total }) : '')
    } catch (e) { onError(errorText(e)) } finally { setBusy(false) }
  }

  return <>
    <div style={{ display: 'grid', gap: 12, gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 170px), 1fr))' }}>
      <div className="field"><label className="field-label" htmlFor="pick-bank">{t('Ngân hàng')}</label>
        <Select id="pick-bank" className="input" value={f.bankId ?? ''} onChange={e => set({ bankId: e.target.value || undefined })}>
          <option value="">{t('Tất cả ngân hàng dùng được')}</option>
          {(banks.data ?? []).map(b => <option key={b.id} value={b.id}>{BANK_TYPE_LABEL[b.type]} · {b.name}</option>)}
        </Select></div>
      <SubjectCategoryFields subjectId={subjectId} categoryId={f.categoryId ?? ''} allowAll
        onSubject={id => { setSubjectId(id); set({ categoryId: undefined }) }} onCategory={id => set({ categoryId: id || undefined })} />
      <div className="field"><label className="field-label" htmlFor="pick-type">{t('Loại')}</label>
        <Select id="pick-type" className="input" value={f.questionType ?? ''} onChange={e => set({ questionType: e.target.value || undefined })}>
          <option value="">{t('Tất cả loại')}</option>{Object.entries(QUESTION_TYPE_LABEL).map(([v, l]) => <option key={v} value={v}>{t(l)}</option>)}
        </Select></div>
      <div className="field"><label className="field-label" htmlFor="pick-diff">{t('Độ khó')}</label>
        <Select id="pick-diff" className="input" value={f.difficulty ?? ''} onChange={e => set({ difficulty: e.target.value || undefined })}>
          <option value="">{t('Tất cả độ khó')}</option>{Object.entries(DIFFICULTY_LABEL).map(([v, l]) => <option key={v} value={v}>{t(l)}</option>)}
        </Select></div>
    </div>
    <form className="input-group" onSubmit={e => { e.preventDefault(); set({ text: text.trim() || undefined }) }}>
      <input className="input" type="search" maxLength={200} aria-label={t('Tìm trong nội dung')} placeholder={t('Tìm trong nội dung câu hỏi')} value={text} onChange={e => setText(e.target.value)} />
      <button type="submit" className="btn btn-secondary">{t('Tìm')}</button>
    </form>
    <div className="row" style={{ justifyContent: 'space-between' }}>
      <span className="small muted">{list.data ? t('{n} câu khớp bộ lọc', { n: list.data.totalElements }) : ''}</span>
      <span className="row" style={{ gap: 8 }}>
        <button type="button" className="btn btn-secondary btn-sm" disabled={!pageFree.length || busy} onClick={() => onReport(onAdd(pageFree))}>{t('Chọn cả trang')}</button>
        <button type="button" className="btn btn-secondary btn-sm" disabled={!list.data?.totalElements || busy} aria-busy={busy} onClick={() => void selectAll()}>{busy ? t('Đang chọn…') : t('Chọn tất cả theo bộ lọc ({n})', { n: list.data?.totalElements ?? 0 })}</button>
        <button type="button" className="btn btn-ghost btn-sm" disabled={!rows.some(q => order.includes(q.id))} onClick={() => onRemove(rows.map(q => q.id))}>{t('Bỏ chọn trang này')}</button>
      </span>
    </div>
    <div className="card" style={{ overflow: 'hidden' }}>
      {list.isLoading ? <LoadingState rows={4} /> : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} />
        : rows.length === 0 ? <EmptyState title={t('Không có câu hỏi đủ điều kiện phù hợp')} description={t('Kích hoạt câu cá nhân; Public phải đang công khai, Organization cần quyền sử dụng.')} />
        : rows.map(q => <PickRow key={q.id} q={q} already={excluded.includes(q.id)} checked={order.includes(q.id)} onToggle={() => onToggle(q)} />)}
      {list.data && <Pagination label={t('Phân trang câu hỏi đủ điều kiện')} page={page} totalPages={list.data.totalPages} onChange={setPage} />}
    </div>
  </>
}

function SetSource({ excluded, order, onAdd, onReport }: {
  excluded: string[]; order: string[]; onAdd: (qs: Question[]) => ReturnType<typeof addToSelection>; onReport: (r: ReturnType<typeof addToSelection>, extra?: string) => void
}) {
  const user = useAuthStore(s => s.user)
  const [setId, setSetId] = useState('')
  const banks = useQuery({ queryKey: ['banks', 'picker'], queryFn: bankApi.allBanks })
  const personal = banks.data?.find(b => b.type === 'PERSONAL' && b.ownerUserId === user?.id)
  const sets = useQuery({ queryKey: ['banks', 'picker-sets', personal?.id], queryFn: () => bankApi.questionSets(personal!.id, 0, 50, { status: 'ACTIVE' }), enabled: !!personal })
  const items = useQuery({ queryKey: ['banks', 'set-eligible', setId], queryFn: () => bankApi.setEligibleItems(setId), enabled: !!setId, retry: false })
  const data: SetEligibleItems | undefined = items.data
  const free = (data?.items ?? []).filter(i => !excluded.includes(i.questionId) && !order.includes(i.questionId))

  return <>
    <div className="field"><label className="field-label" htmlFor="pick-set">{t('Bộ câu hỏi của bạn')}</label>
      <Select id="pick-set" className="input" value={setId} onChange={e => setSetId(e.target.value)}>
        <option value="">{t('Chọn một bộ…')}</option>
        {(sets.data?.content ?? []).map(s => <option key={s.id} value={s.id}>{s.title} · {t('{n} câu', { n: s.items.length })}</option>)}
      </Select></div>
    {sets.isError && <ErrorState error={sets.error} onRetry={() => void sets.refetch()} />}
    {sets.data && !sets.data.content.length && <EmptyState title={t('Chưa có bộ câu hỏi đang dùng')} description={t('Tạo bộ ở Kho cá nhân → Bộ câu hỏi.')} />}
    {setId && (items.isLoading ? <LoadingState rows={3} /> : items.isError ? <ErrorState error={items.error} onRetry={() => void items.refetch()} /> : data && <>
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <span className="small">{t('{n} câu dùng được', { n: data.items.length })}{data.skipped.length ? t(' · {n} câu bị bỏ qua', { n: data.skipped.length }) : ''}</span>
        <button type="button" className="btn btn-primary btn-sm" disabled={!free.length} onClick={() => onReport(onAdd(free.map(i => ({ ...i.question, id: i.questionId }))))}>{t('Chọn {n} câu của bộ', { n: free.length })}</button>
      </div>
      <div className="card" style={{ overflow: 'hidden' }}>
        {data.items.map(i => <PickRow key={i.questionId} q={{ ...i.question, id: i.questionId }} already={excluded.includes(i.questionId)} checked={order.includes(i.questionId)} readOnly
          note={i.versionChanged ? t('Bộ lưu v{saved}; dùng bản đang kích hoạt v{active}', { saved: i.setQuestionVersion, active: i.questionVersion }) : undefined} />)}
      </div>
      {data.skipped.length > 0 && <Notice tone="warning" title={t('Câu không thêm được')}>
        <ul style={{ margin: 0, paddingLeft: 18 }}>{data.skipped.map(s => <li key={`${s.position}-${s.questionId}`} className="small">{t('Vị trí {n}', { n: s.position + 1 })}: {SKIP_REASON[s.reason] ? t(SKIP_REASON[s.reason]) : s.message}</li>)}</ul>
      </Notice>}
    </>)}
  </>
}

function PickRow({ q, already, checked, onToggle, readOnly, note }: { q: Question; already: boolean; checked: boolean; onToggle?: () => void; readOnly?: boolean; note?: string }) {
  return (
    <label className="list-item check" style={{ cursor: already || readOnly ? 'default' : 'pointer', opacity: already ? 0.6 : 1 }}>
      <input type="checkbox" checked={already || checked} disabled={already || readOnly} onChange={onToggle} />
      <span style={{ minWidth: 0 }}>
        <span className="xsmall muted">{t(QUESTION_TYPE_LABEL[q.questionType] ?? '')} · {t(DIFFICULTY_LABEL[q.difficultyLevel] ?? '')} · {q.categoryName} · v{q.questionVersion}{already ? t(' · đã có trong đề') : ''}{note ? ` · ${note}` : ''}</span>
        <span className="truncate-2 small" style={{ display: '-webkit-box', color: 'var(--color-ink)' }}>{q.content}</span>
      </span>
    </label>
  )
}

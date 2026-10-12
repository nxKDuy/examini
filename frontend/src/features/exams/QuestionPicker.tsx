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

type Mode = 'filter' | 'set'
type Flash = { tone: 'info' | 'warning' | 'danger'; text: string } | null

/**
 * Select provider-eligible resources (publication IDs preserve immutable snapshots). The selection is kept
 * across pages, filters and the two sources, ordered, and never holds the same question twice.
 */
export default function QuestionPicker({ excluded, onAdd, onClose }: { excluded: string[]; onAdd: (qs: Question[]) => void; onClose: () => void }) {
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
  const toggle = (q: Question) => { if (order.includes(q.id)) remove([q.id]); else { const r = add([q]); if (r.overflow) setFlash({ tone: 'warning', text: `Đề thi tối đa ${MAX_EXAM_QUESTIONS} câu.` }) } }

  function report(r: ReturnType<typeof addToSelection>, extra = '') {
    const parts = [`Đã thêm ${r.added} câu vào lựa chọn.`]
    if (r.duplicates) parts.push(`${r.duplicates} câu đã có trong đề hoặc đã chọn nên bỏ qua.`)
    if (r.overflow) parts.push(`${r.overflow} câu không thêm vì đề thi tối đa ${MAX_EXAM_QUESTIONS} câu.`)
    if (extra) parts.push(extra)
    setFlash({ tone: r.overflow || extra ? 'warning' : 'info', text: parts.join(' ') })
  }

  const selected = order.map(id => known.get(id)).filter((q): q is Question => !!q)

  return (
    <Dialog size="lg" title="Thêm câu hỏi vào đề" onClose={onClose} busy={busy}
      description="Chỉ hiện câu đủ điều kiện dùng cho đề. Máy chủ kiểm tra lại quyền và điều kiện khi lưu và khi xuất bản."
      footer={<>
        <span className="small muted" style={{ marginRight: 'auto' }} role="status">Đã chọn {order.length} câu · còn chỗ {room}</span>
        <button type="button" className="btn btn-secondary" onClick={onClose} disabled={busy}>Hủy</button>
        <button type="button" className="btn btn-primary" disabled={order.length === 0 || busy} onClick={() => onAdd(selected)}>Thêm {order.length || ''} câu hỏi</button>
      </>}>
      <div className="stack">
        <div className="tabs" role="tablist" aria-label="Nguồn câu hỏi">
          <button type="button" role="tab" className="tab" aria-selected={mode === 'filter'} onClick={() => setMode('filter')}>Lọc câu hỏi</button>
          <button type="button" role="tab" className="tab" aria-selected={mode === 'set'} onClick={() => setMode('set')}>Từ bộ câu hỏi</button>
          <span className="small muted" style={{ marginLeft: 'auto', alignSelf: 'center', paddingRight: 8 }}>Lựa chọn giữ nguyên khi đổi trang, bộ lọc hoặc nguồn</span>
        </div>
        {flash && <Notice tone={flash.tone} live>{flash.text}</Notice>}
        {mode === 'filter'
          ? <FilterSource excluded={excluded} order={order} busy={busy} setBusy={setBusy} onToggle={toggle} onAdd={add} onReport={report} onRemove={remove} onError={text => setFlash({ tone: 'danger', text })} />
          : <SetSource excluded={excluded} order={order} onAdd={add} onReport={report} />}
        {order.length > 0 && <details>
          <summary className="field-label" style={{ cursor: 'pointer' }}>Xem {order.length} câu đã chọn (chưa thêm vào đề)</summary>
          <ol className="stack-sm" style={{ margin: '10px 0 0', paddingLeft: 22 }}>
            {selected.map(q => <li key={q.id} className="small"><span className="truncate-2">{q.content}</span> <button type="button" className="btn btn-ghost btn-sm" onClick={() => remove([q.id])}>Bỏ chọn</button></li>)}
          </ol>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => setOrder([])}>Bỏ chọn tất cả</button>
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
      onReport({ ...r, duplicates: r.duplicates + (res.ids.length - wanted.length) }, res.truncated ? `Bộ lọc khớp ${res.total} câu; hệ thống chỉ chọn tối đa 500 câu đầu.` : '')
    } catch (e) { onError(errorText(e)) } finally { setBusy(false) }
  }

  return <>
    <div style={{ display: 'grid', gap: 12, gridTemplateColumns: 'repeat(auto-fit, minmax(min(100%, 170px), 1fr))' }}>
      <div className="field"><label className="field-label" htmlFor="pick-bank">Ngân hàng</label>
        <select id="pick-bank" className="input" value={f.bankId ?? ''} onChange={e => set({ bankId: e.target.value || undefined })}>
          <option value="">Tất cả ngân hàng dùng được</option>
          {(banks.data ?? []).map(b => <option key={b.id} value={b.id}>{BANK_TYPE_LABEL[b.type]} · {b.name}</option>)}
        </select></div>
      <SubjectCategoryFields subjectId={subjectId} categoryId={f.categoryId ?? ''} allowAll
        onSubject={id => { setSubjectId(id); set({ categoryId: undefined }) }} onCategory={id => set({ categoryId: id || undefined })} />
      <div className="field"><label className="field-label" htmlFor="pick-type">Loại</label>
        <select id="pick-type" className="input" value={f.questionType ?? ''} onChange={e => set({ questionType: e.target.value || undefined })}>
          <option value="">Tất cả loại</option>{Object.entries(QUESTION_TYPE_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
        </select></div>
      <div className="field"><label className="field-label" htmlFor="pick-diff">Độ khó</label>
        <select id="pick-diff" className="input" value={f.difficulty ?? ''} onChange={e => set({ difficulty: e.target.value || undefined })}>
          <option value="">Tất cả độ khó</option>{Object.entries(DIFFICULTY_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}
        </select></div>
    </div>
    <form className="input-group" onSubmit={e => { e.preventDefault(); set({ text: text.trim() || undefined }) }}>
      <input className="input" type="search" maxLength={200} aria-label="Tìm trong nội dung" placeholder="Tìm trong nội dung câu hỏi" value={text} onChange={e => setText(e.target.value)} />
      <button type="submit" className="btn btn-secondary">Tìm</button>
    </form>
    <div className="row" style={{ justifyContent: 'space-between' }}>
      <span className="small muted">{list.data ? `${list.data.totalElements} câu khớp bộ lọc` : ''}</span>
      <span className="row" style={{ gap: 8 }}>
        <button type="button" className="btn btn-secondary btn-sm" disabled={!pageFree.length || busy} onClick={() => onReport(onAdd(pageFree))}>Chọn cả trang</button>
        <button type="button" className="btn btn-secondary btn-sm" disabled={!list.data?.totalElements || busy} aria-busy={busy} onClick={() => void selectAll()}>{busy ? 'Đang chọn…' : `Chọn tất cả theo bộ lọc (${list.data?.totalElements ?? 0})`}</button>
        <button type="button" className="btn btn-ghost btn-sm" disabled={!rows.some(q => order.includes(q.id))} onClick={() => onRemove(rows.map(q => q.id))}>Bỏ chọn trang này</button>
      </span>
    </div>
    <div className="card" style={{ overflow: 'hidden' }}>
      {list.isLoading ? <LoadingState rows={4} /> : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} />
        : rows.length === 0 ? <EmptyState title="Không có câu hỏi đủ điều kiện phù hợp" description="Kích hoạt câu cá nhân; Public phải đang công khai, Organization cần quyền sử dụng." />
        : rows.map(q => <PickRow key={q.id} q={q} already={excluded.includes(q.id)} checked={order.includes(q.id)} onToggle={() => onToggle(q)} />)}
      {list.data && <Pagination label="Phân trang câu hỏi đủ điều kiện" page={page} totalPages={list.data.totalPages} onChange={setPage} />}
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
    <div className="field"><label className="field-label" htmlFor="pick-set">Bộ câu hỏi của bạn</label>
      <select id="pick-set" className="input" value={setId} onChange={e => setSetId(e.target.value)}>
        <option value="">Chọn một bộ…</option>
        {(sets.data?.content ?? []).map(s => <option key={s.id} value={s.id}>{s.title} · {s.items.length} câu</option>)}
      </select></div>
    {sets.isError && <ErrorState error={sets.error} onRetry={() => void sets.refetch()} />}
    {sets.data && !sets.data.content.length && <EmptyState title="Chưa có bộ câu hỏi đang dùng" description="Tạo bộ ở Kho cá nhân → Bộ câu hỏi." />}
    {setId && (items.isLoading ? <LoadingState rows={3} /> : items.isError ? <ErrorState error={items.error} onRetry={() => void items.refetch()} /> : data && <>
      <div className="row" style={{ justifyContent: 'space-between' }}>
        <span className="small">{data.items.length} câu dùng được{data.skipped.length ? ` · ${data.skipped.length} câu bị bỏ qua` : ''}</span>
        <button type="button" className="btn btn-primary btn-sm" disabled={!free.length} onClick={() => onReport(onAdd(free.map(i => ({ ...i.question, id: i.questionId }))))}>Chọn {free.length} câu của bộ</button>
      </div>
      <div className="card" style={{ overflow: 'hidden' }}>
        {data.items.map(i => <PickRow key={i.questionId} q={{ ...i.question, id: i.questionId }} already={excluded.includes(i.questionId)} checked={order.includes(i.questionId)} readOnly
          note={i.versionChanged ? `Bộ lưu v${i.setQuestionVersion}; dùng bản đang kích hoạt v${i.questionVersion}` : undefined} />)}
      </div>
      {data.skipped.length > 0 && <Notice tone="warning" title="Câu không thêm được">
        <ul style={{ margin: 0, paddingLeft: 18 }}>{data.skipped.map(s => <li key={`${s.position}-${s.questionId}`} className="small">Vị trí {s.position + 1}: {SKIP_REASON[s.reason] ?? s.message}</li>)}</ul>
      </Notice>}
    </>)}
  </>
}

function PickRow({ q, already, checked, onToggle, readOnly, note }: { q: Question; already: boolean; checked: boolean; onToggle?: () => void; readOnly?: boolean; note?: string }) {
  return (
    <label className="list-item check" style={{ cursor: already || readOnly ? 'default' : 'pointer', opacity: already ? 0.6 : 1 }}>
      <input type="checkbox" checked={already || checked} disabled={already || readOnly} onChange={onToggle} />
      <span style={{ minWidth: 0 }}>
        <span className="xsmall muted">{QUESTION_TYPE_LABEL[q.questionType]} · {DIFFICULTY_LABEL[q.difficultyLevel]} · {q.categoryName} · v{q.questionVersion}{already ? ' · đã có trong đề' : ''}{note ? ` · ${note}` : ''}</span>
        <span className="truncate-2 small" style={{ display: '-webkit-box', color: 'var(--color-ink)' }}>{q.content}</span>
      </span>
    </label>
  )
}

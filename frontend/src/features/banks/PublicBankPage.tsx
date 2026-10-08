import { useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Globe2, Search } from 'lucide-react'
import './banks.css'
import { bankApi, type PublicQuestion } from './bankApi'
import BankPanel, { QuestionView } from './BankPanel'
import { MAX_SEARCH, authorName, canForkPublic, canUnpublish, excerpt, isPublicOwner, isStatus, publicParams, publicationBadge } from './bankModel'
import { audienceOf } from '../../components/layout/navigation'
import { useAuthStore, hasPersona } from '../../store/authStore'
import { Dialog } from '../../components/Dialog'
import { Badge, EmptyState, ErrorState, Field, LoadingState, Notice, PageHeader, Pagination } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { DIFFICULTY_LABEL, QUESTION_TYPE_LABEL, formatDateTime } from '../../lib/format'
import { useIsNarrow } from '../../lib/hooks'

/** Public question bank: browse and search (Teacher and Student, signed in), copy (Teacher), unpublish (owner). Safe content only: no answer key, no e-mail. */
export default function PublicBankPage() {
  const user = useAuthStore(s => s.user)
  const teacher = hasPersona(user, 'TEACHER') && audienceOf(user) !== 'STUDENT'
  const [text, setText] = useState('')
  const [applied, setApplied] = useState('')
  const [type, setType] = useState('')
  const [difficulty, setDifficulty] = useState('')
  const [author, setAuthor] = useState<{ id: string; name: string } | null>(null)
  const [mineOnly, setMineOnly] = useState(false)
  const [publicationStatus, setPublicationStatus] = useState<'' | 'PUBLISHED' | 'ARCHIVED'>('')
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<PublicQuestion | null>(null)
  const selectedId = selected?.id ?? null
  const narrowList = useIsNarrow(899), narrowPanel = useIsNarrow(1100)

  const authorId = mineOnly ? user?.id ?? '' : author?.id ?? ''
  const params = publicParams({ text: applied, questionType: type, difficulty, author: authorId, page, mine: mineOnly, publicationStatus: mineOnly ? publicationStatus || undefined : undefined })
  const list = useQuery({ queryKey: ['banks', 'public', params], queryFn: () => bankApi.publicList(params), placeholderData: prev => prev })
  const items = list.data?.content ?? []
  const setSelectedId = (id: string | null) => setSelected(items.find(i => i.id === id) ?? null)
  const reset = (fn: () => void) => { fn(); setPage(0); setSelectedId(null) }
  function submit(e: FormEvent) { e.preventDefault(); reset(() => setApplied(text.trim())) }

  return (
    <div className="stack">
      <PageHeader title="Ngân hàng công khai" description={teacher ? 'Câu hỏi do giáo viên xuất bản. Tìm, xem và sao chép về kho cá nhân của bạn.' : 'Câu hỏi do giáo viên xuất bản. Bạn có thể xem, không sao chép được.'} />
      <section className="card card-body stack">
        <form onSubmit={submit} className="row" style={{ alignItems: 'flex-end', flexWrap: 'nowrap' }}>
          <div style={{ flex: 1 }}><Field label="Tìm trong nội dung câu hỏi" htmlFor="pb-text" hint={`Tối đa ${MAX_SEARCH} ký tự.`}><input id="pb-text" className="input" type="search" maxLength={MAX_SEARCH} value={text} onChange={e => setText(e.target.value)} /></Field></div>
          <button type="submit" className="btn btn-primary"><Search size={16} aria-hidden /> Tìm</button>
        </form>
        <div className="bk-filters">
          <label className="row small" style={{ gap: 6 }}>Loại<select className="input" style={{ width: 'auto' }} value={type} onChange={e => reset(() => setType(e.target.value))}><option value="">Tất cả</option>{Object.entries(QUESTION_TYPE_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></label>
          <label className="row small" style={{ gap: 6 }}>Độ khó<select className="input" style={{ width: 'auto' }} value={difficulty} onChange={e => reset(() => setDifficulty(e.target.value))}><option value="">Tất cả</option>{Object.entries(DIFFICULTY_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></label>
          {teacher && <label className="row small" style={{ gap: 6 }}><input type="checkbox" checked={mineOnly} onChange={e => reset(() => { setMineOnly(e.target.checked); setAuthor(null); setPublicationStatus('') })} /> Đã xuất bản của tôi</label>}
          {mineOnly && <label className="row small" style={{ gap: 6 }}>Xuất bản<select className="input" style={{ width: 'auto' }} value={publicationStatus} onChange={e => reset(() => setPublicationStatus(e.target.value as typeof publicationStatus))}><option value="">Tất cả bản của tôi</option><option value="PUBLISHED">Đang công khai</option><option value="ARCHIVED">Đã gỡ công khai</option></select></label>}
          {author && !mineOnly && <span className="row small" style={{ gap: 6 }}>Tác giả: <Badge tone="info">{author.name}</Badge><button type="button" className="btn btn-ghost btn-sm" onClick={() => reset(() => setAuthor(null))}>Bỏ lọc</button></span>}
        </div>
      </section>

      <div className="bk-layout" data-panel={selected ? 'open' : 'closed'}>
        <section className="card" aria-label="Kết quả" style={{ overflow: 'hidden', minWidth: 0 }}>
          {list.isLoading ? <LoadingState rows={4} /> : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} />
            : items.length === 0 ? <EmptyState icon={<Globe2 size={28} />} title="Không có câu hỏi công khai" description={applied || type || difficulty || authorId ? 'Không có câu khớp bộ lọc. Thử bỏ bớt điều kiện.' : 'Chưa có câu hỏi nào được xuất bản.'} />
            : narrowList ? items.map(i => <PublicCard key={i.id} i={i} active={selectedId === i.id} onOpen={() => setSelectedId(i.id)} onAuthor={() => reset(() => { setAuthor({ id: i.sourceOwnerUserId, name: authorName(i) }); setMineOnly(false) })} />)
            : <table className="bk-table"><thead><tr><th scope="col">Câu hỏi</th><th scope="col">Loại</th><th scope="col">Độ khó</th><th scope="col">Tác giả</th><th scope="col">Nguồn</th></tr></thead>
                <tbody>{items.map(i => (
                  <tr key={i.id} aria-selected={selectedId === i.id}>
                    <td><button type="button" className="bk-row-btn" onClick={() => setSelectedId(i.id)}>{excerpt(i.question.content)}</button>{i.publicationStatus === 'ARCHIVED' && <Badge tone="muted">Đã gỡ công khai</Badge>}</td>
                    <td>{QUESTION_TYPE_LABEL[i.question.questionType]}</td><td>{DIFFICULTY_LABEL[i.question.difficultyLevel]}</td>
                    <td><button type="button" className="bk-row-btn" onClick={() => reset(() => { setAuthor({ id: i.sourceOwnerUserId, name: authorName(i) }); setMineOnly(false) })} aria-label={`Lọc theo tác giả ${authorName(i)}`}>{authorName(i)}</button></td>
                    <td className="small">Phiên bản {i.sourceQuestionVersion} · {formatDateTime(i.publishedAt, '-')}</td>
                  </tr>
                ))}</tbody></table>}
          {list.data && <Pagination label="Phân trang ngân hàng công khai" page={page} totalPages={list.data.totalPages} onChange={p => { setPage(p); setSelectedId(null) }} />}
        </section>
        {selected && <PublicPanel key={selected.id} item={selected} teacher={teacher} userId={user?.id} narrow={narrowPanel} onClose={() => setSelectedId(null)} />}
      </div>
    </div>
  )
}

function PublicCard({ i, active, onOpen, onAuthor }: { i: PublicQuestion; active: boolean; onOpen: () => void; onAuthor: () => void }) {
  return <article className="bk-card" aria-current={active} data-archived={i.publicationStatus === 'ARCHIVED'}>
    <button type="button" className="bk-row-btn" onClick={onOpen}>{excerpt(i.question.content, 110)}</button>
    <span className="row xsmall muted" style={{ gap: 8 }}><span>{QUESTION_TYPE_LABEL[i.question.questionType]}</span><span>{DIFFICULTY_LABEL[i.question.difficultyLevel]}</span>
      <button type="button" className="bk-row-btn" onClick={onAuthor} aria-label={`Lọc theo tác giả ${authorName(i)}`}>{authorName(i)}</button>
      <span>Phiên bản {i.sourceQuestionVersion} · {formatDateTime(i.publishedAt, '-')}</span>
      {i.publicationStatus === 'ARCHIVED' && <Badge tone="muted">Đã gỡ công khai</Badge>}
    </span>
  </article>
}

function PublicPanel({ item, teacher, userId, narrow, onClose }: { item: PublicQuestion; teacher: boolean; userId: string | undefined; narrow: boolean; onClose: () => void }) {
  const qc = useQueryClient()
  const detail = useQuery({ queryKey: ['banks', 'public-detail', item.id], queryFn: () => bankApi.publicGet(item.id), initialData: item, staleTime: 0, retry: false })
  const p = detail.data ?? item
  const badge = publicationBadge(p)
  const [flash, setFlash] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const [confirm, setConfirm] = useState(false)
  const fork = useMutation({
    mutationFn: () => bankApi.publicFork(p.id, p.question.questionVersion),
    onSuccess: () => setFlash({ tone: 'success', text: 'Đã sao chép về kho cá nhân thành một câu độc lập (bản nháp). Bản công khai không đổi.' }),
    onError: async e => { setFlash({ tone: 'danger', text: isStatus(e, 409) ? 'Câu này đã được gỡ công khai nên không sao chép được nữa.' : errorText(e) }); await detail.refetch() },
  })
  const unpublish = useMutation({
    mutationFn: () => bankApi.publicUnpublish(p.id),
    onSuccess: async () => { setConfirm(false); setFlash({ tone: 'success', text: 'Đã gỡ công khai. Câu hỏi không còn trong tìm kiếm; bản đã sao chép và đề thi đã xuất bản giữ nguyên.' }); await qc.invalidateQueries({ queryKey: ['banks', 'public'] }); await detail.refetch() },
    onError: async e => { setConfirm(false); setFlash({ tone: 'danger', text: errorText(e) }); await detail.refetch() },
  })
  return (
    <BankPanel title="Câu hỏi công khai" subtitle={`${authorName(p)} · phiên bản nguồn ${p.sourceQuestionVersion}`} narrow={narrow} onClose={onClose}>
      {flash && <Notice tone={flash.tone} live action={flash.tone === 'success' && flash.text.startsWith('Đã sao chép') ? <Link to="/questions" className="btn btn-secondary btn-sm">Mở kho cá nhân</Link> : undefined}>{flash.text}</Notice>}
      <div className="row" style={{ gap: 6 }}><Badge tone={badge.tone}>{badge.text}</Badge>{isPublicOwner(p, userId) && <Badge tone="info">Của bạn</Badge>}</div>
      <section aria-label="Nguồn gốc" className="stack-sm">
        <span className="field-label">Nguồn gốc</span>
        <dl className="small stack-sm" style={{ margin: 0 }}>
          <div><dt className="xsmall muted">Tác giả</dt><dd style={{ margin: 0 }}>{authorName(p)}</dd></div>
          <div><dt className="xsmall muted">Phiên bản nguồn</dt><dd style={{ margin: 0 }}>{p.sourceQuestionVersion}</dd></div>
          <div><dt className="xsmall muted">Xuất bản</dt><dd style={{ margin: 0 }}>{formatDateTime(p.publishedAt, '-')}</dd></div>
          {p.archivedAt && <div><dt className="xsmall muted">Gỡ công khai</dt><dd style={{ margin: 0 }}>{formatDateTime(p.archivedAt, '-')}</dd></div>}
        </dl>
      </section>
      <Notice tone="info">Nội dung công khai không có đáp án.</Notice>
      {detail.isError && <ErrorState error={detail.error} onRetry={() => void detail.refetch()} />}
      <QuestionView question={p.question} />
      <div className="stack-sm">
        {detail.isSuccess && canForkPublic(p, teacher) && <button type="button" className="btn btn-primary" disabled={fork.isPending} onClick={() => { setFlash(null); fork.mutate() }}>{fork.isPending ? 'Đang sao chép…' : 'Sao chép về kho cá nhân'}</button>}
        {!teacher && <p className="xsmall muted">Học sinh chỉ xem; chỉ giáo viên được sao chép.</p>}
        {detail.isSuccess && canUnpublish(p, userId) && <button type="button" className="btn btn-danger-ghost" disabled={unpublish.isPending} onClick={() => { setFlash(null); setConfirm(true) }}>Gỡ công khai</button>}
      </div>
      {confirm && (
        <Dialog title="Gỡ công khai câu hỏi này?" onClose={() => setConfirm(false)} busy={unpublish.isPending} description="Câu hỏi sẽ ẩn khỏi tìm kiếm và không thể sao chép hay chọn vào đề thi mới."
          footer={<><button type="button" className="btn btn-secondary" onClick={() => setConfirm(false)} disabled={unpublish.isPending}>Hủy</button>
            <button type="button" className="btn btn-danger" onClick={() => unpublish.mutate()} disabled={unpublish.isPending}>{unpublish.isPending ? 'Đang gỡ…' : 'Gỡ công khai'}</button></>}>
          <ul className="small stack-sm" style={{ margin: 0, paddingLeft: 18 }}>
            <li>Các bản đã được sao chép về kho cá nhân và các đề thi đã xuất bản giữ nguyên.</li>
            <li>Lịch sử nguồn gốc vẫn được lưu. Muốn đăng lại, xuất bản lại từ kho cá nhân.</li>
          </ul>
        </Dialog>
      )}
    </BankPanel>
  )
}

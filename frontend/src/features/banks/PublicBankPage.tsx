import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BookOpenCheck, Search, Layers3, FileQuestion } from 'lucide-react'
import './banks.css'
import { bankApi, type PublicQuestion } from './bankApi'
import BankPanel, { QuestionView } from './BankPanel'
import { MAX_SEARCH, applyPublicViewStats, authorName, canForkPublic, canUnpublish, excerpt, isStatus, personalForkQueryKeys, publicDetailQueryKey, publicParams, publicationBadge } from './bankModel'
import { audienceOf } from '../../components/layout/navigation'
import { useAuthStore, hasPersona } from '../../store/authStore'
import { Dialog } from '../../components/Dialog'
import { Badge, EmptyState, ErrorState, Field, LoadingState, Notice, PageHeader, Pagination } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { DIFFICULTY_LABEL, QUESTION_TYPE_LABEL, formatDateTime } from '../../lib/format'
import { useIsNarrow } from '../../lib/hooks'

const PERIODS = [{ id: 'DAY', label: '24 giờ' }, { id: 'MONTH', label: '30 ngày' }, { id: 'YEAR', label: '365 ngày' }]

export default function PublicBankPage() {
  const user = useAuthStore(s => s.user)
  const teacher = hasPersona(user, 'TEACHER') && audienceOf(user) !== 'STUDENT'
  const [text, setText] = useState(''), [applied, setApplied] = useState('')
  const [type, setType] = useState(''), [difficulty, setDifficulty] = useState(''), [subjectId, setSubjectId] = useState(''), [categoryId, setCategoryId] = useState('')
  const [subjectText, setSubjectText] = useState(''), [categoryText, setCategoryText] = useState('')
  const [postType, setPostType] = useState(''), [sort, setSort] = useState<'NEWEST' | 'COPY' | 'RATING'>('NEWEST'), [period, setPeriod] = useState('DAY')
  const [author, setAuthor] = useState<{ id: string; name: string } | null>(null), [mineOnly, setMineOnly] = useState(false)
  const [publicationStatus, setPublicationStatus] = useState<'' | 'PUBLISHED' | 'ARCHIVED'>('')
  const [page, setPage] = useState(0), [selectedId, setSelectedId] = useState<string | null>(null)
  const narrowList = useIsNarrow(899), narrowPanel = useIsNarrow(1100)
  const subjects = useQuery({ queryKey: ['banks', 'public-taxonomy', 'SUBJECT', subjectText], queryFn: () => bankApi.publicTaxonomy('SUBJECT', subjectText) })
  const categories = useQuery({ queryKey: ['banks', 'public-taxonomy', 'CATEGORY', categoryText, subjectId], queryFn: () => bankApi.publicTaxonomy('CATEGORY', categoryText, subjectId || undefined) })
  const ranking = sort === 'NEWEST' ? 'NEWEST' : `${sort}_${period}`
  const params = publicParams({ text: applied, questionType: type, difficulty, author: author?.id ?? '', page, mine: mineOnly, publicationStatus: mineOnly ? publicationStatus || undefined : undefined, subjectId, categoryId, postType, ranking })
  const list = useQuery({ queryKey: ['banks', 'public', user?.id ?? 'anonymous', params], queryFn: () => bankApi.publicList(params), placeholderData: prev => prev })
  const items = list.data?.content ?? []
  const reset = (fn: () => void) => { fn(); setPage(0); setSelectedId(null) }
  function submit(e: FormEvent) { e.preventDefault(); reset(() => setApplied(text.trim())) }
  const selectSubject = (id: string) => { setSubjectId(id); setSubjectText(subjects.data?.content.find(x => x.id === id)?.name ?? ''); setCategoryId(''); setCategoryText('') }
  const selectCategory = (id: string) => { const found = categories.data?.content.find(x => x.id === id); reset(() => { setCategoryId(id); setCategoryText(found?.name ?? '') }) }

  return <div className="stack public-bank">
    <PageHeader title="Ngân hàng công khai" description={teacher ? 'Khám phá bài đăng câu hỏi và bộ câu hỏi. Xem snapshot an toàn hoặc sao chép về kho cá nhân.' : 'Khám phá và xem các snapshot câu hỏi công khai. Học sinh có thể đánh giá, không thể sao chép.'} />
    <section className="card card-body stack" aria-label="Bộ lọc bài đăng">
      <form onSubmit={submit} className="row" style={{ alignItems: 'flex-end', flexWrap: 'nowrap' }}>
        <div style={{ flex: 1 }}><Field label="Tìm nội dung, tiêu đề" htmlFor="pb-text" hint={`Tối đa ${MAX_SEARCH} ký tự.`}><input id="pb-text" className="input" type="search" maxLength={MAX_SEARCH} value={text} onChange={e => setText(e.target.value)} /></Field></div>
        <button type="submit" className="btn btn-primary"><Search size={16} aria-hidden /> Tìm</button>
      </form>
      <div className="public-filters">
        <div className="field"><label className="field-label" htmlFor="pb-subject">Môn học</label><input id="pb-subject" className="input" value={subjectText} onChange={e => { setSubjectText(e.target.value); setSubjectId(''); setCategoryId('') }} placeholder="Tất cả môn" autoComplete="off" aria-autocomplete="list" aria-controls="pb-subject-options"/>{!subjectId && subjectText && <div className="taxonomy-options" id="pb-subject-options" role="listbox" aria-label="Môn học phù hợp">{(subjects.data?.content ?? []).map(x => <button type="button" key={x.id} role="option" aria-selected="false" onClick={() => reset(() => selectSubject(x.id))}>{x.name}</button>)}{!subjects.isFetching && !subjects.data?.content.length && <span className="xsmall muted">Không tìm thấy môn học</span>}</div>}</div>
        <div className="field"><label className="field-label" htmlFor="pb-category">Chủ đề / danh mục</label><input id="pb-category" className="input" value={categoryText} onChange={e => { setCategoryText(e.target.value); setCategoryId('') }} placeholder="Tất cả danh mục" autoComplete="off" aria-autocomplete="list" aria-controls="pb-category-options"/>{!categoryId && categoryText && <div className="taxonomy-options" id="pb-category-options" role="listbox" aria-label="Danh mục phù hợp">{(categories.data?.content ?? []).map(x => <button type="button" key={x.id} role="option" aria-selected="false" onClick={() => selectCategory(x.id)}>{x.name}<small>{x.subjectName}</small></button>)}{!categories.isFetching && !categories.data?.content.length && <span className="xsmall muted">Không tìm thấy danh mục</span>}</div>}</div>
        <label className="field"><span className="field-label">Kiểu bài đăng</span><select className="input" value={postType} onChange={e => reset(() => setPostType(e.target.value))}><option value="">Tất cả</option><option value="SINGLE_QUESTION">Một câu</option><option value="QUESTION_SET">Bộ câu hỏi</option></select></label>
        <label className="field"><span className="field-label">Loại câu</span><select className="input" value={type} onChange={e => reset(() => setType(e.target.value))}><option value="">Tất cả</option>{Object.entries(QUESTION_TYPE_LABEL).map(([v,l]) => <option key={v} value={v}>{l}</option>)}</select></label>
        <label className="field"><span className="field-label">Độ khó</span><select className="input" value={difficulty} onChange={e => reset(() => setDifficulty(e.target.value))}><option value="">Tất cả</option>{Object.entries(DIFFICULTY_LABEL).map(([v,l]) => <option key={v} value={v}>{l}</option>)}</select></label>
        <label className="field"><span className="field-label">Sắp xếp</span><select className="input" value={sort} onChange={e => reset(() => setSort(e.target.value as typeof sort))}><option value="NEWEST">Mới nhất</option><option value="COPY">Top lượt sao chép</option><option value="RATING">Top đánh giá</option></select></label>
        {sort !== 'NEWEST' && <label className="field"><span className="field-label">Khoảng thời gian</span><select className="input" value={period} onChange={e => reset(() => setPeriod(e.target.value))}>{PERIODS.map(p => <option key={p.id} value={p.id}>{p.label}</option>)}</select></label>}
        {teacher && <label className="public-check"><input type="checkbox" checked={mineOnly} onChange={e => reset(() => { setMineOnly(e.target.checked); setAuthor(null); setPublicationStatus('') })}/> Bài đã xuất bản của tôi</label>}
        {mineOnly && <label className="field"><span className="field-label">Trạng thái</span><select className="input" value={publicationStatus} onChange={e => reset(() => setPublicationStatus(e.target.value as typeof publicationStatus))}><option value="">Tất cả</option><option value="PUBLISHED">Đang công khai</option><option value="ARCHIVED">Đã gỡ công khai</option></select></label>}
      </div>
      <div className="public-chips" aria-label="Bộ lọc đang dùng">
        {subjectId && <button type="button" className="badge badge-info" onClick={() => reset(() => selectSubject(''))}>{subjectText} ×</button>}
        {categoryId && <button type="button" className="badge badge-info" onClick={() => reset(() => { setCategoryId(''); setCategoryText('') })}>{categoryText} ×</button>}
        {author && <button type="button" className="badge badge-info" onClick={() => reset(() => setAuthor(null))}>Tác giả: {author.name} ×</button>}
      </div>
    </section>
    <div className="public-results" data-panel={selectedId ? 'open' : 'closed'}>
      <div className="public-results-main">
        {list.isFetching && !list.isLoading && <span className="xsmall muted" role="status">Đang cập nhật bài đăng…</span>}
        {list.isLoading ? <LoadingState rows={5}/> : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()}/>
          : items.length === 0 ? <EmptyState icon={<BookOpenCheck size={28}/>} title="Không có bài đăng phù hợp" description="Thử đổi từ khóa hoặc bỏ bớt bộ lọc."/>
          : <section className="public-grid" aria-label="Bài đăng công khai">{items.map(post => <PublicCard key={post.id} post={post} active={selectedId === post.id} onOpen={() => setSelectedId(post.id)} onAuthor={() => reset(() => { setAuthor({ id: post.sourceOwnerUserId, name: authorName(post) }); setMineOnly(false) })}/>)}</section>}
        {list.data && <Pagination label="Phân trang bài đăng công khai" page={page} totalPages={list.data.totalPages} onChange={p => { setPage(p); setSelectedId(null) }}/>}
      </div>
      {selectedId && <PublicPanel key={selectedId} id={selectedId} teacher={teacher} userId={user?.id} narrow={narrowPanel} onClose={() => setSelectedId(null)}/>}
    </div>
    {narrowList && <span className="sr-only">Thẻ có thể mở bằng bàn phím</span>}
  </div>
}

function PublicCard({ post, active, onOpen, onAuthor }: { post: PublicQuestion; active: boolean; onOpen: () => void; onAuthor: () => void }) {
  const bundle = post.postType === 'QUESTION_SET'
  return <article className="public-post" aria-current={active} data-archived={post.publicationStatus === 'ARCHIVED'}>
    <button type="button" className="public-post-cover" onClick={onOpen} aria-label={`Mở ${post.title || (bundle ? 'bộ câu hỏi' : 'câu hỏi')}`}><span className="public-cover-icon">{bundle ? <Layers3 size={28}/> : <FileQuestion size={28}/>}</span><span>{post.subjects?.map(s => s.name).join(' · ') || post.question.categoryName || 'Ngân hàng câu hỏi'}</span></button>
    <div className="public-post-body">
      <div className="row" style={{justifyContent:'space-between'}}><Badge tone="info">{bundle ? `Bộ · ${post.itemCount ?? post.items?.length ?? 0} câu` : 'Một câu'}</Badge>{post.publicationStatus === 'ARCHIVED' && <Badge tone="muted">Đã gỡ công khai</Badge>}</div>
      <button type="button" className="public-post-title" onClick={onOpen}>{post.title?.trim() || excerpt(post.question.content, 76)}</button>
      <p className="public-post-snippet">{excerpt(post.description || post.question.content, 120)}</p>
      <div className="public-post-tags">{post.subjects?.slice(0,2).map(s => <span key={s.id}>{s.name}</span>)}{post.categories?.slice(0,2).map(c => <span key={c.id}>{c.name}</span>)}<span>{QUESTION_TYPE_LABEL[post.question.questionType] ?? post.question.questionType}</span><span>{DIFFICULTY_LABEL[post.question.difficultyLevel] ?? post.question.difficultyLevel}</span></div>
      <div className="public-post-meta"><button type="button" className="bk-row-btn" onClick={onAuthor}>{authorName(post)}</button><span>v{post.postVersion ?? post.sourceQuestionVersion} · {formatDateTime(post.publishedAt, '-')}</span></div>
      <div className="public-post-stats"><span>{post.viewsTotal == null ? 'Chưa có số liệu xem' : `${post.viewsTotal} lượt xem`}</span><span>{post.copiesTotal == null ? 'Chưa có số liệu sao chép' : `${post.copiesTotal} lượt sao chép`}</span><span>{post.ratingCount == null ? 'Chưa có số liệu đánh giá' : post.ratingCount ? `${post.averageRating ?? '—'}/10 · ${post.ratingCount} đánh giá` : 'Chưa có đánh giá'}</span></div>
      <button type="button" className="btn btn-secondary btn-sm" onClick={onOpen}>Xem chi tiết</button>
    </div>
  </article>
}

function PublicPanel({ id, teacher, userId, narrow, onClose }: { id: string; teacher: boolean; userId: string | undefined; narrow: boolean; onClose: () => void }) {
  const qc = useQueryClient()
  const detailKey = useMemo(() => publicDetailQueryKey(id, userId), [id, userId])
  const detail = useQuery({ queryKey: detailKey, queryFn: () => bankApi.publicGet(id), staleTime: Infinity, refetchOnWindowFocus: false, retry: false })
  const p = detail.data
  const [flash, setFlash] = useState<{tone:'success'|'danger';text:string}|null>(null), [confirm, setConfirm] = useState(false), [rating, setRating] = useState(''), [viewError, setViewError] = useState(false)
  const forkSingle = useMutation({ mutationFn: ({itemId, version}: {itemId?:string;version:number}) => itemId ? bankApi.publicForkItem(id,itemId,version) : bankApi.publicFork(id,version), onSuccess: async () => { setFlash({tone:'success',text:'Đã tạo bản sao độc lập dạng bản nháp trong kho cá nhân.'}); await Promise.all([detail.refetch(), ...personalForkQueryKeys(userId, false).map(queryKey => qc.invalidateQueries({queryKey})), qc.invalidateQueries({queryKey:['banks','public']})]) }, onError: async e => { setFlash({tone:'danger',text:isStatus(e,409)?'Snapshot đã được gỡ hoặc thay đổi; hãy tải lại trước khi thử lại.':errorText(e)}); await detail.refetch() } })
  const forkSet = useMutation({ mutationFn: () => bankApi.publicForkSet(id), onSuccess: async () => { setFlash({tone:'success',text:'Đã sao chép bộ vào kho cá nhân. Các câu được sao chép ở trạng thái bản nháp; kích hoạt câu rồi cập nhật bộ trước khi xuất bản lại.'}); await Promise.all([detail.refetch(), ...personalForkQueryKeys(userId, true).map(queryKey => qc.invalidateQueries({queryKey})), qc.invalidateQueries({queryKey:['banks','public']})]) }, onError: async e => { setFlash({tone:'danger',text:isStatus(e,409)?'Snapshot đã được gỡ hoặc thay đổi; hãy tải lại trước khi thử lại.':errorText(e)}); await detail.refetch() } })
  const unpublish = useMutation({ mutationFn: () => bankApi.publicUnpublish(id), onSuccess: async () => { setConfirm(false); setFlash({tone:'success',text:'Đã gỡ công khai. Bài đăng ẩn khỏi tìm kiếm; bản sao và đề đã xuất bản giữ nguyên.'}); await qc.invalidateQueries({queryKey:['banks','public']}); await detail.refetch() }, onError: async e => { setConfirm(false); setFlash({tone:'danger',text:errorText(e)}); await detail.refetch() } })
  const rate = useMutation({ mutationFn: () => bankApi.publicRate(id, Number(rating)), onSuccess: async () => { setFlash({tone:'success',text:'Đã lưu đánh giá của bạn.'}); setRating(''); await detail.refetch(); await qc.invalidateQueries({queryKey:['banks','public']}) }, onError: e => setFlash({tone:'danger',text:errorText(e)}) })
  const recordView = useCallback(async () => {
    setViewError(false)
    try {
      const view = await bankApi.publicView(id)
      qc.setQueryData(detailKey, (old: PublicQuestion | undefined) => old ? applyPublicViewStats(old, view) : old)
      await Promise.all([qc.invalidateQueries({queryKey:['banks','public']}), ...(qc.getQueryData(detailKey) ? [] : [qc.invalidateQueries({queryKey:detailKey})])])
    } catch {
      setViewError(true)
    }
  }, [id, qc, detailKey])
  useEffect(() => { void recordView() }, [recordView])
  const isOwner = !!userId && p?.sourceOwnerUserId === userId
  if (!p) return <BankPanel title="Bài đăng công khai" subtitle="Đang tải snapshot" narrow={narrow} onClose={onClose}>{detail.isLoading ? <LoadingState rows={4}/> : <ErrorState error={detail.error} onRetry={() => void detail.refetch()}/>}</BankPanel>
  const items = p.items?.length ? [...p.items].sort((a,b) => a.position-b.position) : [{id:p.id,position:1,sourceQuestionId:p.sourceQuestionId,sourceQuestionVersion:p.sourceQuestionVersion,question:p.question}]
  const badge = publicationBadge(p)
  return <BankPanel title={p.title || (p.postType === 'QUESTION_SET' ? 'Bộ câu hỏi' : 'Câu hỏi công khai')} subtitle={`${authorName(p)} · snapshot v${p.postVersion ?? p.sourceQuestionVersion}`} narrow={narrow} onClose={onClose}>
    {flash && <Notice tone={flash.tone} live action={flash.tone==='success' && <Link to="/questions" className="btn btn-secondary btn-sm">Mở kho cá nhân</Link>}>{flash.text}</Notice>}
    {viewError && <Notice tone="warning" live action={<button type="button" className="btn btn-secondary btn-sm" onClick={() => void recordView()}>Thử ghi nhận lại</button>}>Chưa cập nhật được số lượt xem từ máy chủ.</Notice>}
    <div className="row"><Badge tone={badge.tone}>{badge.text}</Badge>{isOwner && <Badge tone="info">Của bạn</Badge>}<Badge tone="muted">{p.postType === 'QUESTION_SET' ? `Bộ · ${p.itemCount ?? items.length} câu` : 'Một câu'}</Badge></div>
    {p.description && <p>{p.description}</p>}
    <section className="public-detail-stats" aria-label="Số liệu thật"><span>{p.viewsTotal ?? '—'}<small>{p.viewsTotal == null ? 'chưa có số liệu xem' : 'lượt xem'}</small></span><span>{p.copiesTotal ?? '—'}<small>{p.copiesTotal == null ? 'chưa có số liệu sao chép' : 'lượt sao chép'}</small></span><span>{p.ratingCount == null ? '—' : p.ratingCount ? `${p.averageRating ?? '—'}/10` : 'Chưa có'}<small>{p.ratingCount == null ? 'chưa có số liệu đánh giá' : p.ratingCount ? `${p.ratingCount} đánh giá` : 'đánh giá'}</small></span></section>
    <section aria-label="Nguồn gốc" className="stack-sm"><strong className="field-label">Nguồn gốc</strong><dl className="small stack-sm" style={{margin:0}}><div><dt className="xsmall muted">Tác giả</dt><dd style={{margin:0}}>{authorName(p)}</dd></div><div><dt className="xsmall muted">Bản chụp nguồn</dt><dd style={{margin:0}}>Phiên bản {p.postVersion ?? p.sourceQuestionVersion}{p.items?.length ? ` · ${p.items.length} câu` : ''}</dd></div><div><dt className="xsmall muted">Xuất bản</dt><dd style={{margin:0}}>{formatDateTime(p.publishedAt,'-')}</dd></div>{p.archivedAt && <div><dt className="xsmall muted">Gỡ công khai</dt><dd style={{margin:0}}>{formatDateTime(p.archivedAt,'-')}</dd></div>}</dl></section>
    <Notice tone="info">Chỉ hiển thị nội dung và lựa chọn an toàn; đáp án và lời giải không được công khai.</Notice>
    {items.map((item,i) => <section className="stack-sm public-item" key={item.id}><h3 className="small">{p.postType === 'QUESTION_SET' ? `Câu ${i+1}` : 'Nội dung'}</h3><QuestionView question={item.question}/>{teacher && canForkPublic(p,true) && <button type="button" className="btn btn-secondary btn-sm" disabled={forkSingle.isPending} onClick={() => forkSingle.mutate({itemId:p.postType==='QUESTION_SET'?item.id:undefined,version:item.sourceQuestionVersion})}>Sao chép câu này</button>}</section>)}
    <div className="stack-sm">
      {teacher && p.postType === 'QUESTION_SET' && canForkPublic(p,true) && <button type="button" className="btn btn-primary" disabled={forkSet.isPending} onClick={() => forkSet.mutate()}>{forkSet.isPending?'Đang sao chép…':'Sao chép cả bộ về kho cá nhân'}</button>}
      {!teacher && <p className="xsmall muted">Học sinh chỉ xem và có thể đánh giá; chỉ giáo viên được sao chép.</p>}
      {!isOwner && <form className="public-rating" onSubmit={e => {e.preventDefault(); if (Number(rating)>=0 && Number(rating)<=10) rate.mutate()}}><label className="field"><span className="field-label">Đánh giá (0–10)</span><input className="input" type="number" min="0" max="10" step="any" required value={rating} onChange={e => setRating(e.target.value)} aria-describedby="rating-hint"/><span id="rating-hint" className="field-hint">Có thể nhập số thập phân. Đánh giá hiện tại của bạn: {p.myRating ?? 'chưa có'}.</span></label><button className="btn btn-secondary" disabled={!rating || rate.isPending}>{rate.isPending?'Đang lưu…':'Gửi đánh giá'}</button></form>}
      {isOwner && <p className="xsmall muted">Chủ bài đăng không thể tự đánh giá.</p>}
      {canUnpublish(p,userId) && <button type="button" className="btn btn-danger-ghost" onClick={() => setConfirm(true)}>Gỡ công khai</button>}
    </div>
    {confirm && <Dialog title="Gỡ bài đăng công khai?" onClose={() => setConfirm(false)} busy={unpublish.isPending} description="Bài sẽ ẩn khỏi tìm kiếm và không thể sao chép hoặc chọn mới vào đề thi." footer={<><button type="button" className="btn btn-secondary" onClick={() => setConfirm(false)}>Hủy</button><button type="button" className="btn btn-danger" disabled={unpublish.isPending} onClick={() => unpublish.mutate()}>Gỡ công khai</button></>}><p>Các bản sao và đề thi đã xuất bản giữ nguyên.</p></Dialog>}
  </BankPanel>
}

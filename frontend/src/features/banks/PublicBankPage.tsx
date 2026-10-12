import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link, useLocation, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { BookOpenCheck, Search, Layers3, FileQuestion, SlidersHorizontal, ArrowLeft, Bookmark, Eye, Copy, Star, Plus, Check } from 'lucide-react'
import './banks.css'
import { bankApi, type PublicQuestion } from './bankApi'
import BankPanel, { QuestionView } from './BankPanel'
import PublishToPublicDialog from './PublishToPublicDialog'
import RatingStar from './RatingStar'
import { MAX_SEARCH, broadTopic, cardText, canBookmark, postStats, updatedDate, applyPublicViewStats, authorName, canForkPublic, canUnpublish, copiedBadge, isStatus, personalForkQueryKeys, publicDetailQueryKey, publicParams, publicationBadge } from './bankModel'
import { audienceOf } from '../../components/layout/navigation'
import { useAuthStore, hasPersona } from '../../store/authStore'
import { Dialog } from '../../components/Dialog'
import { Badge, EmptyState, ErrorState, LoadingState, Notice, PageHeader, Pagination } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { optimisticallySetBookmark, rollbackBookmark } from './publicBookmarkCache'
import { DIFFICULTY_LABEL, QUESTION_TYPE_LABEL, formatDateTime } from '../../lib/format'

const PERIODS = [{ id: 'DAY', label: '24 giờ' }, { id: 'MONTH', label: '30 ngày' }, { id: 'YEAR', label: '365 ngày' }]

export default function PublicBankPage() {
  const user = useAuthStore(s => s.user)
  const teacher = hasPersona(user, 'TEACHER') && audienceOf(user) !== 'STUDENT'
  const canSave = canBookmark(audienceOf(user))
  const navigate = useNavigate(), location = useLocation(), { postId } = useParams<{postId:string}>(), [searchParams,setSearchParams] = useSearchParams()
  const [text,setText] = useState(searchParams.get('q') ?? ''), [subjectText,setSubjectText] = useState(searchParams.get('subject') ?? ''), [categoryText,setCategoryText] = useState(searchParams.get('category') ?? '')
  const [advancedOpen,setAdvancedOpen] = useState(false)
  const applied = searchParams.get('q') ?? ''
  const rawType = searchParams.get('type') ?? '', type = Object.hasOwn(QUESTION_TYPE_LABEL, rawType) ? rawType : ''
  const rawDifficulty = searchParams.get('difficulty') ?? '', difficulty = Object.hasOwn(DIFFICULTY_LABEL, rawDifficulty) ? rawDifficulty : ''
  const subjectId = searchParams.get('subjectId') ?? '', categoryId = searchParams.get('categoryId') ?? ''
  const rawPostType = searchParams.get('kind') ?? '', postType = ['SINGLE_QUESTION','QUESTION_SET'].includes(rawPostType) ? rawPostType : ''
  const rawSort = searchParams.get('sort') ?? '', sort = (['COPY','RATING'].includes(rawSort) ? rawSort : 'NEWEST') as 'NEWEST' | 'COPY' | 'RATING'
  const rawPeriod = searchParams.get('period') ?? '', period = PERIODS.some(p => p.id === rawPeriod) ? rawPeriod : 'DAY'
  const authorId = searchParams.get('author') ?? '', authorNameFilter = searchParams.get('authorName') ?? ''
  const savedOnly = canSave && searchParams.get('saved') === '1', mineOnly = !savedOnly && searchParams.get('mine') === '1', rawPublication = searchParams.get('publication') ?? ''
  const publicationStatus = rawPublication === 'PUBLISHED' || rawPublication === 'ARCHIVED' ? rawPublication : ''
  const parsedPage = Number(searchParams.get('page') ?? 0), page = Number.isInteger(parsedPage) && parsedPage >= 0 ? parsedPage : 0
  const subjects = useQuery({ queryKey: ['banks', 'public-taxonomy', 'SUBJECT', subjectText], queryFn: () => bankApi.publicTaxonomy('SUBJECT', subjectText), enabled: !postId && !subjectId && !!subjectText })
  const categories = useQuery({ queryKey: ['banks', 'public-taxonomy', 'CATEGORY', categoryText, subjectId], queryFn: () => bankApi.publicTaxonomy('CATEGORY', categoryText, subjectId || undefined), enabled: !postId && !categoryId && !!categoryText })
  const ranking = sort === 'NEWEST' ? 'NEWEST' : `${sort}_${period}`
  const params = publicParams({ text: applied, questionType: type, difficulty, author: authorId, page, mine: mineOnly, saved: savedOnly, publicationStatus: mineOnly ? publicationStatus || undefined : undefined, subjectId, categoryId, postType, ranking })
  const list = useQuery({ queryKey: ['banks', 'public', user?.id ?? 'anonymous', params], queryFn: () => bankApi.publicList(params), placeholderData: prev => prev, enabled: !postId })
  const qc = useQueryClient()
  const [bookmarkError, setBookmarkError] = useState('')
  const [publishing, setPublishing] = useState(false), [publishFlash, setPublishFlash] = useState<string | null>(null)
  const bookmark = useMutation({
    mutationFn: ({ id, saved }: { id: string; saved: boolean }) => saved ? bankApi.bookmark(id) : bankApi.unbookmark(id),
    onMutate: ({ id, saved }) => optimisticallySetBookmark(qc, id, user?.id, saved),
    onError: (error, _variables, snapshot) => { if (snapshot) rollbackBookmark(qc, snapshot); setBookmarkError(errorText(error)) },
    onSuccess: async (_result, variables) => { setBookmarkError(''); await Promise.all([qc.invalidateQueries({ queryKey: ['banks', 'public', user?.id ?? 'anonymous'] }), qc.invalidateQueries({ queryKey: publicDetailQueryKey(variables.id, user?.id) })]) },
  })
  const items = list.data?.content ?? []
  const changeParams = (changes: Record<string,string | undefined>, resetPage = true) => setSearchParams(current => {
    const next = new URLSearchParams(current)
    Object.entries(changes).forEach(([key,value]) => value ? next.set(key,value) : next.delete(key))
    if (resetPage) next.delete('page')
    return next
  })
  function submit(e: FormEvent) { e.preventDefault(); changeParams({q:text.trim() || undefined}) }
  const selectSubject = (id: string) => {
    const name = subjects.data?.content.find(x => x.id === id)?.name ?? subjectText
    setSubjectText(name); setCategoryText(''); changeParams({subjectId:id || undefined,subject:name || undefined,categoryId:undefined,category:undefined})
  }
  const selectCategory = (id: string) => {
    const name = categories.data?.content.find(x => x.id === id)?.name ?? categoryText
    setCategoryText(name); changeParams({categoryId:id || undefined,category:name || undefined})
  }
  useEffect(() => { const params = new URLSearchParams(location.search); setText(params.get('q') ?? ''); setSubjectText(params.get('subject') ?? ''); setCategoryText(params.get('category') ?? '') }, [location.search])
  const activeFilterCount = [applied,subjectId,categoryId,type,difficulty,postType,authorId,mineOnly ? 'mine' : '',publicationStatus,sort !== 'NEWEST' ? sort : ''].filter(Boolean).length

  if (postId) {
    const back = () => location.state?.fromPublicBank ? navigate(-1) : navigate(`/public-bank${location.search}`)
    return <div className="public-detail-route"><PublicPanel key={`${postId}:${user?.id ?? 'anonymous'}`} id={postId} teacher={teacher} canSave={canSave} userId={user?.id} onClose={back} page /></div>
  }

  return <div className="stack public-bank">
    <PageHeader title="Ngân hàng công khai" description={teacher ? 'Khám phá bài đăng câu hỏi và bộ câu hỏi. Xem snapshot an toàn hoặc sao chép về kho cá nhân.' : 'Khám phá và xem các snapshot câu hỏi công khai. Học sinh có thể đánh giá, không thể sao chép.'}  actions={teacher && <button type="button" className="btn btn-primary btn-icon" aria-label="Đăng bài lên Ngân hàng công khai" title="Đăng bài lên Ngân hàng công khai" aria-haspopup="dialog" onClick={() => { setPublishFlash(null); setPublishing(true) }}><Plus size={20} aria-hidden /></button>} />
    {publishFlash && <Notice tone="success" live>{publishFlash}</Notice>}
    <section className="card public-filter-card" aria-label="Bộ lọc bài đăng">
      <form onSubmit={submit} className="public-toolbar">
        <div className="public-primary-filters">
          <div className="field public-search-field">
            <label className="field-label" htmlFor="pb-text">Tìm nội dung, tiêu đề</label>
            <div className="input-group"><input id="pb-text" className="input" type="search" maxLength={MAX_SEARCH} value={text} onChange={e => setText(e.target.value)} title={`Tối đa ${MAX_SEARCH} ký tự`} aria-describedby="pb-search-hint" /><button type="submit" className="btn btn-primary"><Search size={16} aria-hidden /> Tìm</button></div>
            <span id="pb-search-hint" className="sr-only">Tối đa {MAX_SEARCH} ký tự.</span>
          </div>
          <TaxonomyFilter id="pb-subject" label="Môn học" value={subjectText} suggestions={subjects.data?.content ?? []} loading={subjects.isFetching} error={subjects.isError} placeholder="Tất cả môn"
            onChange={value => { setSubjectText(value); changeParams({ subjectId: undefined, subject: undefined, categoryId: undefined, category: undefined }) }} onSelect={selectSubject} />
          <TaxonomyFilter id="pb-category" label="Chủ đề / danh mục" value={categoryText} suggestions={categories.data?.content ?? []} loading={categories.isFetching} error={categories.isError} placeholder="Tất cả danh mục"
            onChange={value => { setCategoryText(value); changeParams({ categoryId: undefined, category: undefined }) }} onSelect={selectCategory} />
          <label className="field"><span className="field-label">Sắp xếp</span><select className="input" value={sort} onChange={e => changeParams({ sort: e.target.value === 'NEWEST' ? undefined : e.target.value })}><option value="NEWEST">Mới nhất</option><option value="COPY">Top lượt sao chép</option><option value="RATING">Top đánh giá</option></select></label>
          {sort !== 'NEWEST' && <label className="field"><span className="field-label">Khoảng thời gian</span><select className="input" value={period} onChange={e => changeParams({ period: e.target.value === 'DAY' ? undefined : e.target.value })}>{PERIODS.map(p => <option key={p.id} value={p.id}>{p.label}</option>)}</select></label>}
          <button type="button" className="btn btn-secondary public-advanced-toggle" aria-expanded={advancedOpen} aria-controls="pb-advanced" onClick={() => setAdvancedOpen(value => !value)}><SlidersHorizontal size={16} aria-hidden /> Bộ lọc{activeFilterCount > 0 ? ' · ' + activeFilterCount : ''}</button>
        </div>
        {advancedOpen && <div id="pb-advanced" className="public-advanced-filters">
          <label className="field"><span className="field-label">Kiểu bài đăng</span><select className="input" value={postType} onChange={e => changeParams({ kind: e.target.value || undefined })}><option value="">Tất cả</option><option value="SINGLE_QUESTION">Một câu</option><option value="QUESTION_SET">Bộ câu hỏi</option></select></label>
          <label className="field"><span className="field-label">Loại câu</span><select className="input" value={type} onChange={e => changeParams({ type: e.target.value || undefined })}><option value="">Tất cả</option>{Object.entries(QUESTION_TYPE_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></label>
          <label className="field"><span className="field-label">Độ khó</span><select className="input" value={difficulty} onChange={e => changeParams({ difficulty: e.target.value || undefined })}><option value="">Tất cả</option>{Object.entries(DIFFICULTY_LABEL).map(([v, l]) => <option key={v} value={v}>{l}</option>)}</select></label>
          {canSave && <label className="public-check"><input type="checkbox" checked={savedOnly} onChange={e => changeParams({ saved: e.target.checked ? '1' : undefined, mine: undefined, publication: undefined })} /> Bài đã lưu</label>}
          {teacher && <label className="public-check"><input type="checkbox" checked={mineOnly} onChange={e => changeParams({ mine: e.target.checked ? '1' : undefined, saved: undefined, author: undefined, authorName: undefined, publication: undefined })} /> Bài đã xuất bản của tôi</label>}
          {mineOnly && <label className="field"><span className="field-label">Trạng thái bài đăng</span><select className="input" value={publicationStatus} onChange={e => changeParams({ publication: e.target.value || undefined })}><option value="">Đang công khai và đã gỡ</option><option value="PUBLISHED">Đang công khai</option><option value="ARCHIVED">Đã gỡ công khai</option></select></label>}
          {authorId && <span className="public-filter-author small">Tác giả: {authorNameFilter || 'Unknown user'}</span>}
        </div>}
      </form>
      <div className="public-chips" aria-label="Bộ lọc đang dùng">
        {applied && <button type="button" className="badge badge-info" onClick={() => { setText(''); changeParams({q:undefined}) }}>Tìm: {applied} ×</button>}
        {subjectId && <button type="button" className="badge badge-info" onClick={() => { setSubjectText(''); changeParams({ subjectId: undefined, subject: undefined, categoryId: undefined, category: undefined }) }}>{subjectText} ×</button>}
        {categoryId && <button type="button" className="badge badge-info" onClick={() => { setCategoryText(''); changeParams({ categoryId: undefined, category: undefined }) }}>{categoryText} ×</button>}
        {authorId && <button type="button" className="badge badge-info" onClick={() => changeParams({ author: undefined, authorName: undefined })}>Tác giả: {authorNameFilter || 'Unknown user'} ×</button>}
        {postType && <button type="button" className="badge badge-info" onClick={() => changeParams({ kind: undefined })}>{postType === 'QUESTION_SET' ? 'Bộ câu hỏi' : 'Một câu'} ×</button>}
        {type && <button type="button" className="badge badge-info" onClick={() => changeParams({ type: undefined })}>{QUESTION_TYPE_LABEL[type] ?? type} ×</button>}
        {difficulty && <button type="button" className="badge badge-info" onClick={() => changeParams({ difficulty: undefined })}>{DIFFICULTY_LABEL[difficulty] ?? difficulty} ×</button>}
        {canSave && savedOnly && <button type="button" className="badge badge-info" onClick={() => changeParams({ saved: undefined })}>Bài đã lưu ×</button>}
        {mineOnly && <button type="button" className="badge badge-info" onClick={() => changeParams({ mine: undefined, publication: undefined })}>Bài của tôi{publicationStatus ? ' · ' + (publicationStatus === 'ARCHIVED' ? 'Đã gỡ' : 'Đang công khai') : ''} ×</button>}
        {sort !== 'NEWEST' && <button type="button" className="badge badge-info" onClick={() => changeParams({sort:undefined,period:undefined})}>{sort === 'COPY' ? 'Top lượt sao chép' : 'Top đánh giá'} · {PERIODS.find(p => p.id === period)?.label ?? '24 giờ'} ×</button>}
      </div>
    </section>
    {mineOnly && <Notice tone="info">Kho này hiển thị bài đăng và snapshot đã xuất bản của bạn. Lưu trữ bài nguồn không sửa nội dung snapshot hoặc đề thi đã xuất bản.</Notice>}
    {bookmarkError && <Notice tone="danger" live>{bookmarkError}</Notice>}
    {list.isFetching && !list.isLoading && <span className="xsmall muted" role="status">Đang cập nhật bài đăng…</span>}
    {list.isLoading ? <LoadingState rows={5} /> : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} />
      : items.length === 0 ? <EmptyState icon={<BookOpenCheck size={28} />} title="Không có bài đăng phù hợp" description="Thử đổi từ khóa hoặc bỏ bớt bộ lọc." />
      : <section className="public-grid" aria-label="Bài đăng công khai">{items.map(post => <PublicCard key={post.id} post={post} canSave={canSave} bookmarkBusy={bookmark.isPending} onBookmark={() => bookmark.mutate({ id: post.id, saved: !post.isSaved })}
          onOpen={() => navigate('/public-bank/' + post.id + location.search, { state: { fromPublicBank: true } })}
          onAuthor={() => changeParams({ author: post.sourceOwnerUserId, authorName: authorName(post), mine: undefined, publication: undefined })} />)}</section>}
    {list.data && <Pagination label="Phân trang bài đăng công khai" page={page} totalPages={list.data.totalPages} onChange={p => changeParams({ page: p ? String(p) : undefined }, false)} />}
    {publishing && <PublishToPublicDialog onClose={() => setPublishing(false)} onPublished={message => { setPublishing(false); setPublishFlash(message); void qc.invalidateQueries({ queryKey: ['banks'] }) }} />}
  </div>
}

function TaxonomyFilter({ id, label, value, suggestions, loading, error, placeholder, onChange, onSelect }: {
  id: string; label: string; value: string; suggestions: { id: string; name: string; subjectName?: string }[]; loading: boolean; error: boolean
  placeholder: string; onChange: (value: string) => void; onSelect: (id: string) => void
}) {
  return <div className="field">
    <label className="field-label" htmlFor={id}>{label}</label>
    <input id={id} className="input" value={value} onChange={e => onChange(e.target.value)} placeholder={placeholder} autoComplete="off" aria-autocomplete="list" aria-controls={id + '-options'} />
    {value && <div className="taxonomy-options" id={id + '-options'} role="listbox" aria-label={label + ' phù hợp'}>
      {suggestions.map(x => <button type="button" key={x.id} role="option" aria-selected="false" onClick={() => onSelect(x.id)}>{x.name}{x.subjectName && <small>{x.subjectName}</small>}</button>)}
      {loading && <span className="xsmall muted" role="status">Đang tìm…</span>}
      {error && <span className="xsmall" role="alert">Không tải được danh mục. Thử sửa từ khóa.</span>}
      {!loading && !error && !suggestions.length && <span className="xsmall muted">Không tìm thấy</span>}
    </div>}
  </div>
}
const STAT_ICON = { views: Eye, copies: Copy, rating: Star } as const

/** One post = one card (a set stays ONE card). The whole card opens the detail page through the title; buttons and the author link stay independently clickable. */
function PublicCard({ post, onOpen, onAuthor, onBookmark, bookmarkBusy, canSave }: { post: PublicQuestion; onOpen: () => void; onAuthor: () => void; onBookmark: () => void; bookmarkBusy: boolean; canSave: boolean }) {
  const bundle = post.postType === 'QUESTION_SET'
  const copied = copiedBadge(post), topic = broadTopic(post), updated = updatedDate(post)
  const { title, summary } = cardText(post)
  const Kind = bundle ? Layers3 : FileQuestion
  return <article className="public-post" data-archived={post.publicationStatus === 'ARCHIVED'} data-saved={post.isSaved ? 'true' : undefined} data-copied={copied ? 'true' : undefined}>
    <div className="public-post-top">
      <span className="public-post-kind"><Kind size={15} aria-hidden />{bundle ? `Bộ · ${post.itemCount ?? post.items?.length ?? 0} câu` : 'Một câu'}</span>
      {post.publicationStatus === 'ARCHIVED' && <span className="public-post-flag" data-tone="muted">Đã gỡ công khai</span>}
      {copied && <span className="sr-only">{copied}</span>}
    </div>
    <h3 className="public-post-heading"><button type="button" className="public-post-title" onClick={onOpen} aria-label={`Mở chi tiết: ${title}`}><span>{title}</span></button></h3>
    <p className="public-post-snippet" aria-hidden={!summary || undefined}>{summary}</p>
    <p className="public-post-topic">{topic ? <>{topic.text}{topic.more > 0 && <span className="muted"> +{topic.more}</span>}</> : <span className="muted">Chưa phân loại</span>}</p>
    <div className="public-post-meta"><button type="button" className="bk-row-btn" onClick={onAuthor} aria-label={`Lọc theo tác giả ${authorName(post)}`}>{authorName(post)}</button><span>{updated ? `Cập nhật ${updated}` : ''}</span></div>
    <div className="public-post-stats" role="group" aria-label="Số liệu bài đăng">{postStats(post).map(st => { const Icon = STAT_ICON[st.key]; return <span key={st.key} role="img" aria-label={st.label} title={st.label}>{st.key === 'rating' ? <RatingStar filled={!st.empty} /> : <Icon size={14} aria-hidden />}{st.value}</span> })}</div>
    <div className="public-post-actions"><button type="button" className="btn btn-secondary btn-sm" onClick={onOpen}>Xem chi tiết</button>{canSave && post.publicationStatus === 'PUBLISHED' && <button type="button" className="btn btn-secondary btn-sm btn-save" aria-pressed={!!post.isSaved} aria-label={post.isSaved ? 'Bỏ lưu bài đăng' : 'Lưu bài đăng'} disabled={bookmarkBusy} onClick={onBookmark}><Bookmark size={15} aria-hidden />{bookmarkBusy ? 'Đang lưu…' : post.isSaved ? 'Đã lưu' : 'Lưu'}</button>}</div>
  </article>
}

function PublicPanel({ id, teacher, canSave, userId, onClose, page = false }: { id: string; teacher: boolean; canSave: boolean; userId: string | undefined; onClose: () => void; page?: boolean }) {
  const qc = useQueryClient()
  const detailKey = useMemo(() => publicDetailQueryKey(id, userId), [id, userId])
  const detail = useQuery({ queryKey: detailKey, queryFn: () => bankApi.publicGet(id), staleTime: Infinity, refetchOnWindowFocus: false, retry: false })
  const p = detail.data
  const [flash, setFlash] = useState<{tone:'success'|'danger';text:string}|null>(null), [confirm, setConfirm] = useState(false), [rating, setRating] = useState(''), [viewError, setViewError] = useState(false)
  const bookmark = useMutation({
    mutationFn: (saved: boolean) => saved ? bankApi.bookmark(id) : bankApi.unbookmark(id),
    onMutate: saved => optimisticallySetBookmark(qc, id, userId, saved),
    onError: (e, _saved, snapshot) => { if (snapshot) rollbackBookmark(qc, snapshot); setFlash({tone:'danger',text:errorText(e)}) },
    onSuccess: async (_result, saved) => { setFlash({tone:'success',text:saved ? 'Đã lưu bài đăng.' : 'Đã bỏ lưu bài đăng.'}); await Promise.all([qc.invalidateQueries({queryKey:['banks','public',userId ?? 'anonymous']}), qc.invalidateQueries({queryKey:detailKey})]) },
  })
  const forkSingle = useMutation({ mutationFn: ({itemId, version}: {itemId?:string;version:number}) => itemId ? bankApi.publicForkItem(id,itemId,version) : bankApi.publicFork(id,version), onSuccess: async () => { setFlash({tone:'success',text:'Đã tạo bản sao độc lập dạng bản nháp trong kho cá nhân. Thao tác đã được ghi nhận trên máy chủ.'}); await Promise.all([detail.refetch(), ...personalForkQueryKeys(userId, false).map(queryKey => qc.invalidateQueries({queryKey})), qc.invalidateQueries({queryKey:['banks','public']})]) }, onError: async e => { setFlash({tone:'danger',text:isStatus(e,409)?'Snapshot đã được gỡ hoặc thay đổi; hãy tải lại trước khi thử lại.':errorText(e)}); await detail.refetch() } })
  const forkSet = useMutation({ mutationFn: () => bankApi.publicForkSet(id), onSuccess: async () => { setFlash({tone:'success',text:'Đã sao chép bộ vào kho cá nhân. Các câu được sao chép ở trạng thái bản nháp; thao tác đã được ghi nhận trên máy chủ.'}); await Promise.all([detail.refetch(), ...personalForkQueryKeys(userId, true).map(queryKey => qc.invalidateQueries({queryKey})), qc.invalidateQueries({queryKey:['banks','public']})]) }, onError: async e => { setFlash({tone:'danger',text:isStatus(e,409)?'Snapshot đã được gỡ hoặc thay đổi; hãy tải lại trước khi thử lại.':errorText(e)}); await detail.refetch() } })
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
  const copyBusy = forkSingle.isPending || forkSet.isPending
  if (!p) return page
    ? <section className="public-detail-page"><button type="button" className="btn btn-secondary" onClick={onClose}><ArrowLeft size={16} aria-hidden/> Quay lại danh sách</button><h1>Bài đăng công khai</h1>{detail.isLoading ? <LoadingState rows={4}/> : <ErrorState error={detail.error} onRetry={() => void detail.refetch()}/>}</section>
    : <BankPanel title="Bài đăng công khai" subtitle="Đang tải snapshot" narrow={false} onClose={onClose}>{detail.isLoading ? <LoadingState rows={4}/> : <ErrorState error={detail.error} onRetry={() => void detail.refetch()}/>}</BankPanel>
  const items = p.items?.length ? [...p.items].sort((a,b) => a.position-b.position) : [{id:p.id,position:1,sourceQuestionId:p.sourceQuestionId,sourceQuestionVersion:p.sourceQuestionVersion,question:p.question,isCopied:p.isCopied}]
  const badge = publicationBadge(p)
  const copied = copiedBadge(p)
  const title = p.title || (p.postType === 'QUESTION_SET' ? 'Bộ câu hỏi' : 'Câu hỏi công khai')
  const content = <>
    {flash && <Notice tone={flash.tone} live action={flash.tone==='success' && <Link to="/questions" className="btn btn-secondary btn-sm">Mở kho cá nhân</Link>}>{flash.text}</Notice>}
    {viewError && <Notice tone="warning" live action={<button type="button" className="btn btn-secondary btn-sm" onClick={() => void recordView()}>Thử ghi nhận lại</button>}>Chưa cập nhật được số lượt xem từ máy chủ.</Notice>}
    <div className="row"><Badge tone={badge.tone}>{badge.text}</Badge>{copied && <span className="tag-copied"><Check size={13} aria-hidden />{copied}</span>}{isOwner && <Badge tone="info">Của bạn</Badge>}<Badge tone="muted">{p.postType === 'QUESTION_SET' ? `Bộ · ${p.itemCount ?? items.length} câu` : 'Một câu'}</Badge></div>
    {p.description && <p>{p.description}</p>}
    {isOwner && <Notice tone="info">Bài đăng là snapshot cố định của phiên bản nguồn. Thay đổi hoặc lưu trữ câu nguồn không sửa snapshot này hay đề thi đã xuất bản.</Notice>}
    <section className="public-detail-stats" aria-label="Số liệu thật"><span>{p.viewsTotal ?? '—'}<small>{p.viewsTotal == null ? 'chưa có số liệu xem' : 'lượt xem'}</small></span><span>{p.copiesTotal ?? '—'}<small>{p.copiesTotal == null ? 'chưa có số liệu sao chép' : 'lượt sao chép'}</small></span><span><RatingStar filled={!!p.ratingCount && p.averageRating != null} size={16} />{p.ratingCount == null ? '—' : p.ratingCount ? `${p.averageRating ?? '—'}/10` : 'Chưa có'}<small>{p.ratingCount == null ? 'chưa có số liệu đánh giá' : p.ratingCount ? `${p.ratingCount} đánh giá` : 'đánh giá'}</small></span></section>
    <section aria-label="Nguồn gốc" className="stack-sm"><strong className="field-label">Nguồn gốc</strong><dl className="small stack-sm" style={{margin:0}}><div><dt className="xsmall muted">Tác giả</dt><dd style={{margin:0}}>{authorName(p)}</dd></div><div><dt className="xsmall muted">Bản chụp nguồn</dt><dd style={{margin:0}}>Phiên bản {p.postVersion ?? p.sourceQuestionVersion}{p.items?.length ? ` · ${p.items.length} câu` : ''}</dd></div><div><dt className="xsmall muted">Cập nhật nội dung bản này</dt><dd style={{margin:0}}>{formatDateTime(p.contentUpdatedAt,'-')}</dd></div><div><dt className="xsmall muted">Xuất bản</dt><dd style={{margin:0}}>{formatDateTime(p.publishedAt,'-')}</dd></div>{p.archivedAt && <div><dt className="xsmall muted">Gỡ công khai</dt><dd style={{margin:0}}>{formatDateTime(p.archivedAt,'-')}</dd></div>}</dl></section>
    <Notice tone="info">Chỉ hiển thị nội dung và lựa chọn an toàn; đáp án và lời giải không được công khai.</Notice>
    {items.map((item,i) => <section className="stack-sm public-item" key={item.id}><h3 className="small">{p.postType === 'QUESTION_SET' ? `Câu ${i+1}` : 'Nội dung'} {item.isCopied && <span className="tag-copied"><Check size={13} aria-hidden />Đã sao chép</span>}</h3><QuestionView question={item.question}/>{teacher && canForkPublic(p,true) && <button type="button" className="btn btn-secondary btn-sm" disabled={copyBusy} onClick={() => forkSingle.mutate({itemId:p.postType==='QUESTION_SET'?item.id:undefined,version:item.sourceQuestionVersion})}>{forkSingle.isPending?'Đang sao chép…':'Sao chép câu này'}</button>}</section>)}
    <div className="stack-sm">
      {teacher && p.postType === 'QUESTION_SET' && canForkPublic(p,true) && <button type="button" className="btn btn-primary" disabled={copyBusy} onClick={() => forkSet.mutate()}>{forkSet.isPending?'Đang sao chép…':'Sao chép cả bộ về kho cá nhân'}</button>}
      {!teacher && <p className="xsmall muted">Học sinh chỉ xem và có thể đánh giá; chỉ giáo viên được sao chép.</p>}
      {canSave && p.publicationStatus === 'PUBLISHED' && <button type="button" className="btn btn-secondary btn-save" aria-pressed={!!p.isSaved} disabled={bookmark.isPending} onClick={() => bookmark.mutate(!p.isSaved)}><Bookmark size={15} aria-hidden/>{bookmark.isPending ? 'Đang lưu…' : p.isSaved ? 'Bỏ lưu bài đăng' : 'Lưu bài đăng'}</button>}
      {!isOwner && <form className="public-rating" onSubmit={e => {e.preventDefault(); if (Number(rating)>=0 && Number(rating)<=10) rate.mutate()}}><label className="field"><span className="field-label"><RatingStar key={String(p.myRating)} filled={p.myRating != null} size={16} pop={p.myRating != null} />Đánh giá (0–10)</span><input className="input" type="number" min="0" max="10" step="any" required value={rating} onChange={e => setRating(e.target.value)} aria-describedby="rating-hint"/><span id="rating-hint" className="field-hint">Có thể nhập số thập phân. Đánh giá hiện tại của bạn: {p.myRating ?? 'chưa có'}.</span></label><button className="btn btn-secondary" disabled={!rating || rate.isPending}>{rate.isPending?'Đang lưu…':'Gửi đánh giá'}</button></form>}
      {isOwner && <p className="xsmall muted">Chủ bài đăng không thể tự đánh giá.</p>}
      {canUnpublish(p,userId) && <button type="button" className="btn btn-danger-ghost" onClick={() => setConfirm(true)}>Gỡ công khai</button>}
    </div>
    {confirm && <Dialog title="Gỡ bài đăng công khai?" onClose={() => setConfirm(false)} busy={unpublish.isPending} description="Bài sẽ ẩn khỏi tìm kiếm và không thể sao chép hoặc chọn mới vào đề thi." footer={<><button type="button" className="btn btn-secondary" onClick={() => setConfirm(false)}>Hủy</button><button type="button" className="btn btn-danger" disabled={unpublish.isPending} onClick={() => unpublish.mutate()}>Gỡ công khai</button></>}><p>Các bản sao và đề thi đã xuất bản giữ nguyên.</p></Dialog>}
  </>
  return page
    ? <section className="public-detail-page" aria-labelledby="public-detail-title"><header className="public-detail-header"><button type="button" className="btn btn-secondary" onClick={onClose}><ArrowLeft size={16} aria-hidden/> Quay lại</button><div><h1 id="public-detail-title">{title}</h1><p>{authorName(p)} · snapshot v{p.postVersion ?? p.sourceQuestionVersion}</p></div></header>{content}</section>
    : <BankPanel title={title} subtitle={`${authorName(p)} · snapshot v${p.postVersion ?? p.sourceQuestionVersion}`} narrow={false} onClose={onClose}>{content}</BankPanel>
}

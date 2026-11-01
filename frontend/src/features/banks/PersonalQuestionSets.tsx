import { useRef, useState } from 'react'
import { Link, useBlocker, useNavigate, useParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { ArrowDown, ArrowLeft, ArrowUp, Layers3, Plus } from 'lucide-react'
import { bankApi, type Bank, type BankQuestion, type QuestionSet } from './bankApi'
import { useAuthStore } from '../../store/authStore'
import { Dialog } from '../../components/Dialog'
import { Badge, EmptyState, ErrorState, LoadingState, Notice, PageHeader, Pagination } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { excerpt, isStatus } from './bankModel'
import { CopySyncEntry } from './CopySync'
import { t, useT } from '../../lib/i18n'
import Select from '../../components/Select'

const SETS_PER_PAGE = 6
const QUESTIONS_PER_PAGE = 20
// The sets tab of /questions; the editor returns here so the user lands back on the list they came from.
const SETS_HOME = '/questions?view=sets'

export default function PersonalQuestionSets() {
  useT()
  const user = useAuthStore(s => s.user), qc = useQueryClient(), navigate = useNavigate()
  const [page, setPage] = useState(0), [search, setSearch] = useState(''), [queryText, setQueryText] = useState(''), [status, setStatus] = useState<'ACTIVE' | 'ARCHIVED' | ''>(''), [confirm, setConfirm] = useState<QuestionSet | null>(null), [flash, setFlash] = useState<{ tone: 'success' | 'danger'; text: string } | null>(null)
  const banks = useQuery({ queryKey: ['banks','personal-sets',user?.id], queryFn: bankApi.allBanks, enabled: !!user })
  const personal = banks.data?.find(b => b.type === 'PERSONAL' && b.ownerUserId === user?.id)
  const sets = useQuery({ queryKey: ['banks','question-sets',user?.id,personal?.id,queryText,status,page], queryFn: () => bankApi.questionSets(personal!.id,page,SETS_PER_PAGE,{text:queryText,status:status || undefined}), enabled: !!personal })
  const archive = useMutation({ mutationFn: (s: QuestionSet) => bankApi.archiveQuestionSet(s.id), onSuccess: async () => { setConfirm(null); setFlash({ tone: 'success', text: t('Đã lưu trữ bộ câu hỏi.') }); await qc.invalidateQueries({queryKey:['banks','question-sets']}) }, onError: e => setFlash({ tone: 'danger', text: errorText(e) }) })
  if (!user) return null
  return <section className="card stack" aria-label={t('Bộ câu hỏi cá nhân')}>
    <div className="card-header row" style={{justifyContent:'space-between'}}><div><h2 className="small">{t('Bộ câu hỏi')}</h2><p className="xsmall muted">{t('Gom câu cá nhân đang dùng thành bộ để soạn, dùng lại hoặc lưu trữ. Đăng bộ lên cộng đồng từ nút + ở Ngân hàng công khai.')}</p></div><button className="btn btn-primary btn-sm" onClick={() => navigate('/questions/sets/new')}><Plus size={15}/> {t('Tạo bộ')}</button></div>
    <form className="filter-row card-body" onSubmit={e=>{e.preventDefault();setQueryText(search.trim().slice(0,200));setPage(0)}}><div className="field filter-grow"><label className="field-label" htmlFor="set-search">{t('Tìm bộ theo tên')}</label><div className="input-group"><input id="set-search" className="input" type="search" maxLength={200} title={t('Tối đa 200 ký tự')} value={search} onChange={e=>setSearch(e.target.value)}/><button type="submit" className="btn btn-secondary">{t('Tìm')}</button></div></div><label className="field"><span className="field-label">{t('Trạng thái bộ')}</span><Select className="input" value={status} onChange={e=>{setStatus(e.target.value as typeof status);setPage(0)}}><option value="">{t('Tất cả')}</option><option value="ACTIVE">{t('Đang dùng')}</option><option value="ARCHIVED">{t('Đã lưu trữ')}</option></Select></label></form>
    {flash && <Notice tone={flash.tone} live>{flash.text}</Notice>}
    {banks.isLoading || sets.isLoading ? <LoadingState rows={2}/> : banks.isError ? <ErrorState error={banks.error} onRetry={() => void banks.refetch()}/> : sets.isError ? <ErrorState error={sets.error} onRetry={() => void sets.refetch()}/> : !personal ? <EmptyState title={t('Chưa có kho cá nhân')}/> : !sets.data?.content.length ? <EmptyState icon={<Layers3 size={25}/>} title={t('Chưa có bộ câu hỏi')} description={t('Chọn câu đang dùng để tạo một bộ.')}/> : <div className="set-list">{sets.data.content.map(s => <article className="set-row" key={s.id}><div><strong>{s.title}</strong><p className="xsmall muted">{s.items.length} {t('câu · v')}{s.version} · {s.description || t('Không có mô tả')}</p><div className="xsmall">{s.status === 'ARCHIVED' ? <Badge tone="muted">{t('Đã lưu trữ')}</Badge> : <Badge tone={s.status === 'ACTIVE' ? 'success' : 'warning'}>{s.status === 'ACTIVE' ? t('Đang dùng') : t('Bản nháp')}</Badge>}</div></div><div className="row"><button className="btn btn-secondary btn-sm" disabled={s.status==='ARCHIVED'} onClick={() => navigate(`/questions/sets/${s.id}`)}>{t('Sửa bộ')}</button>{s.status!=='ARCHIVED' && <button className="btn btn-danger-ghost btn-sm" onClick={() => setConfirm(s)}>{t('Lưu trữ')}</button>}</div></article>)}</div>}
    {sets.data && <Pagination label={t('Phân trang bộ câu hỏi cá nhân')} page={page} totalPages={sets.data.totalPages} onChange={setPage} />}
    {confirm && <Dialog title={t('Lưu trữ bộ câu hỏi?')} onClose={() => setConfirm(null)} busy={archive.isPending} description={t('Bộ sẽ không còn chỉnh sửa hoặc đăng mới. Bài đăng công khai đã có vẫn giữ nguyên.')} footer={<><button className="btn btn-secondary" onClick={() => setConfirm(null)}>{t('Hủy')}</button><button className="btn btn-danger" disabled={archive.isPending} onClick={() => archive.mutate(confirm)}>{t('Lưu trữ')}</button></>} />}
  </section>
}

export function PersonalQuestionSetEditorPage() {
  useT()
  const user = useAuthStore(s => s.user), navigate = useNavigate(), { setId } = useParams<{setId:string}>(), qc = useQueryClient()
  const banks = useQuery({queryKey:['banks','personal-sets',user?.id],queryFn:bankApi.allBanks,enabled:!!user})
  const personal = banks.data?.find(b => b.type === 'PERSONAL' && b.ownerUserId === user?.id)
  const set = useQuery({queryKey:['banks','question-set',user?.id,setId],queryFn:()=>bankApi.questionSet(setId!),enabled:!!user && !!setId,retry:false})
  if (!user) return null
  if (banks.isLoading || (setId && set.isLoading)) return <LoadingState rows={5}/>
  if (banks.isError) return <ErrorState error={banks.error} onRetry={()=>void banks.refetch()}/>
  if (!personal) return <EmptyState title={t('Chưa có kho cá nhân')} action={<Link to="/questions" className="btn btn-secondary">{t('Quay lại kho câu hỏi')}</Link>}/>
  if (setId && (set.isError || !set.data)) return <div className="stack"><PageHeader title={t('Sửa bộ câu hỏi')} actions={<button type="button" className="btn btn-secondary" onClick={()=>navigate(SETS_HOME)}>{t('Quay lại')}</button>}/><ErrorState error={set.error} onRetry={()=>void set.refetch()}/></div>
  return <SetEditor key={setId ?? 'new'} bank={personal} initial={set.data} onClose={()=>navigate(SETS_HOME)} onSaved={()=>{void qc.invalidateQueries({queryKey:['banks','question-sets']});navigate(SETS_HOME)}} />
}

function SetEditor({ bank, initial, onClose, onSaved }: { bank: Bank; initial?: QuestionSet; onClose: () => void; onSaved: () => void }) {
  useT()
  const qc = useQueryClient(), [title,setTitle] = useState(initial?.title ?? ''), [description,setDescription] = useState(initial?.description ?? ''), [questionPage,setQuestionPage] = useState(0), [failure,setFailure] = useState('')
  const [questionSearch,setQuestionSearch] = useState(''), [questionText,setQuestionText] = useState(''), [saved,setSaved] = useState(false)
  const allowLeave = useRef(false)
  const [selected,setSelected] = useState<Map<string,BankQuestion>>(() => new Map((initial?.items ?? []).map(i => [i.sourceQuestionId,{bankId:bank.id,question:{id:i.sourceQuestionId,questionVersion:i.sourceQuestionVersion,content:i.question.content,questionType:i.question.questionType,difficultyLevel:i.question.difficultyLevel,categoryName:i.question.categoryName,options:i.question.options,status:i.question.status ?? 'DRAFT'} as BankQuestion['question']}])) )
  const [order,setOrder] = useState<string[]>(() => [...(initial?.items ?? [])].sort((a,b)=>a.position-b.position).map(i=>i.sourceQuestionId))
  const questions = useQuery({ queryKey:['banks','personal-set-questions',bank.id,questionText,questionPage], queryFn: () => bankApi.personalQuestions(bank.id,questionPage,QUESTIONS_PER_PAGE,questionText) })
  const ordered = order.map(id=>selected.get(id)).filter((q):q is BankQuestion=>!!q)
  const originalItems = [...(initial?.items ?? [])].sort((a,b)=>a.position-b.position)
  const dirty = !saved && !allowLeave.current && (title !== (initial?.title ?? '') || description !== (initial?.description ?? '') || ordered.length !== originalItems.length || ordered.some((q,i)=>q.question.id !== originalItems[i]?.sourceQuestionId || q.question.questionVersion !== originalItems[i]?.sourceQuestionVersion))
  const blocker = useBlocker(() => !allowLeave.current && dirty)
  const save = useMutation({ mutationFn: () => initial ? bankApi.updateQuestionSet(initial.id,{title:title.trim(),description:description.trim()||undefined,items:ordered.map(x=>({questionId:x.question.id,questionVersion:x.question.questionVersion})),expectedVersion:initial.version}) : bankApi.createQuestionSet(bank.id,{title:title.trim(),description:description.trim()||undefined,items:ordered.map(x=>({questionId:x.question.id,questionVersion:x.question.questionVersion}))}), onSuccess: async () => {allowLeave.current=true;setSaved(true);await qc.invalidateQueries({queryKey:['banks','question-sets']});onSaved()}, onError: e => setFailure(isStatus(e,409)?t('Một câu hoặc phiên bản bộ đã thay đổi. Tải lại bộ và chọn lại câu trước khi lưu; dữ liệu cũ chưa bị ghi đè.'):errorText(e))})
  function toggle(q: BankQuestion) {
    setSelected(prev=>{const next=new Map(prev);if(next.has(q.question.id))next.delete(q.question.id);else next.set(q.question.id,q);return next})
    setOrder(prev=>prev.includes(q.question.id)?prev.filter(id=>id!==q.question.id):[...prev,q.question.id])
  }
  function move(id:string,delta:number){setOrder(prev=>{const from=prev.indexOf(id),to=from+delta;if(from<0||to<0||to>=prev.length)return prev;const next=[...prev];[next[from],next[to]]=[next[to],next[from]];return next})}
  return <div className="stack set-editor-page">
    <PageHeader title={initial?t('Sửa bộ câu hỏi'):t('Tạo bộ câu hỏi')} crumbs={[{label:t('Kho cá nhân'),to:'/questions'},{label:t('Bộ câu hỏi'),to:SETS_HOME}]} description={`${initial ? `${t('Phiên bản v{0}', { 0: initial.version })} · ${initial.status === 'ACTIVE' ? t('Đang dùng') : t('Đã lưu trữ')}. ` : ''}${t('Bộ chỉ chứa câu ACTIVE, lưu bộ không xuất bản Public; Question DRAFT phải kích hoạt riêng.')}${initial ? t(' Lưu bộ gửi expectedVersion để tránh ghi đè.') : ''}`} actions={<button type="button" className="btn btn-secondary" onClick={onClose}><ArrowLeft size={16} aria-hidden/> {t('Quay lại')}</button>} />
    {initial?.status === 'ACTIVE' && <section className="card card-body"><CopySyncEntry kind="set" id={initial.id} /></section>}
    <section className="card card-body stack">
      <label className="field"><span className="field-label">{t('Tên bộ')}</span><input className="input" maxLength={200} value={title} onChange={e=>setTitle(e.target.value)} required/></label>
      <label className="field"><span className="field-label">{t('Mô tả')}</span><textarea className="input" maxLength={2000} value={description} onChange={e=>setDescription(e.target.value)}/></label>
      <section className="stack-sm" aria-label={t('Thứ tự câu trong bộ')}><strong className="small">{t('Thứ tự trong bộ ·')} {ordered.length} {t('câu')}</strong>{ordered.length ? ordered.map((q,index)=><div className="list-item row set-order-row" key={q.question.id}><span className="set-order-number">{index+1}</span><span className="small" style={{flex:1}}>{excerpt(q.question.content,120)}<small className="xsmall muted" style={{display:'block'}}>v{q.question.questionVersion} · {q.question.status === 'DRAFT' ? t('Bản nháp') : q.question.status === 'ARCHIVED' ? t('Đã lưu trữ') : 'ACTIVE'}</small></span><button type="button" className="btn btn-secondary btn-icon btn-sm" aria-label={t('Đưa câu {0} lên', { 0: index+1 })} disabled={index===0} onClick={()=>move(q.question.id,-1)}><ArrowUp size={15}/></button><button type="button" className="btn btn-secondary btn-icon btn-sm" aria-label={t('Đưa câu {0} xuống', { 0: index+1 })} disabled={index===ordered.length-1} onClick={()=>move(q.question.id,1)}><ArrowDown size={15}/></button><button type="button" className="btn btn-ghost btn-sm" onClick={()=>toggle(q)}>{t('Bỏ chọn')}</button></div>) : <p className="xsmall muted">{t('Chưa chọn câu nào.')}</p>}</section>
      <section className="stack-sm"><strong className="small">{t('Câu ACTIVE trong kho cá nhân')}</strong><form className="filter-row" onSubmit={e=>{e.preventDefault();setQuestionText(questionSearch.trim().slice(0,200));setQuestionPage(0)}}><div className="field filter-grow"><label className="field-label" htmlFor="set-question-search">{t('Tìm trong nội dung')}</label><div className="input-group"><input id="set-question-search" className="input" type="search" maxLength={200} title={t('Tối đa 200 ký tự')} value={questionSearch} onChange={e=>setQuestionSearch(e.target.value)}/><button type="submit" className="btn btn-secondary">{t('Tìm câu')}</button></div></div></form>{ordered.length>=100&&<Notice tone="warning">{t('Bộ đã đủ 100 câu. Bỏ chọn một câu để thêm câu khác.')}</Notice>}{questions.isLoading?<LoadingState rows={4}/>:questions.isError?<ErrorState error={questions.error} onRetry={()=>void questions.refetch()}/>:<div className="set-questions">{(questions.data?.content ?? []).map(q=>{const chosen=selected.get(q.question.id);return <label key={q.question.id} className="list-item check"><input type="checkbox" checked={!!chosen} disabled={!chosen&&ordered.length>=100} onChange={()=>toggle(q)}/><span className="small">{excerpt(q.question.content,160)}<small className="xsmall muted" style={{display:'block'}}>v{q.question.questionVersion} · {q.question.categoryName}{chosen&&chosen.question.questionVersion!==q.question.questionVersion?t(' · Đã đổi phiên bản; bỏ chọn rồi chọn lại để dùng bản mới.'):''}</small></span></label>})}{!questions.data?.content.length&&<EmptyState title={t('Không có câu ACTIVE khớp tìm kiếm')}/>}</div>}{questions.data&&<Pagination label={t('Phân trang câu ACTIVE')} page={questionPage} totalPages={questions.data.totalPages} onChange={setQuestionPage}/>}</section>
      {failure&&<Notice tone="danger" live>{failure}</Notice>}
      <div className="row" style={{justifyContent:'flex-end'}}><button type="button" className="btn btn-secondary" onClick={onClose}>{t('Hủy')}</button><button type="button" className="btn btn-primary" disabled={!title.trim() || ordered.length<1 || save.isPending} onClick={()=>save.mutate()}>{save.isPending?t('Đang lưu…'):t('Lưu bộ · {0} câu', { 0: ordered.length })}</button></div>
    </section>
    {blocker.state === 'blocked' && <Dialog title={t('Bạn có thay đổi chưa lưu')} description={t('Rời khỏi trang sẽ bỏ các thay đổi trong bộ câu hỏi.')} onClose={()=>blocker.reset()} footer={<><button type="button" className="btn btn-secondary" onClick={()=>blocker.reset()}>{t('Tiếp tục chỉnh sửa')}</button><button type="button" className="btn btn-danger" onClick={()=>blocker.proceed()}>{t('Rời khỏi trang')}</button></>}>{t('Bộ chỉ được lưu khi bạn chọn Lưu bộ; không có tự động lưu.')}</Dialog>}
  </div>
}

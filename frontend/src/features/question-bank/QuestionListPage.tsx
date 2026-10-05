import { useState, useRef } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { apiError, questionBankApi, type Filters, type Question, type ImportResult } from './questionApi'
import { useAuthStore } from '../../store/authStore'
import QuestionModal from './QuestionModal'

const statuses = ['DRAFT', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'ARCHIVED']
const types = ['MULTIPLE_CHOICE', 'TRUE_FALSE', 'SHORT_ANSWER', 'ESSAY']
const methods = ['MANUAL', 'IMPORT', 'AI_GENERATED', 'COPIED']
const sources = ['NONE', 'USER_DOCUMENT', 'EXTERNAL_WEB', 'ORGANIZATION_BANK', 'PUBLIC_BANK', 'SYSTEM_BANK']
const labels: Record<string, string> = { DRAFT: 'Bản nháp', PENDING_REVIEW: 'Chờ duyệt', APPROVED: 'Đã duyệt', REJECTED: 'Từ chối', ARCHIVED: 'Lưu trữ', MULTIPLE_CHOICE: 'Trắc nghiệm', TRUE_FALSE: 'Đúng/Sai', SHORT_ANSWER: 'Trả lời ngắn', ESSAY: 'Tự luận', EASY: 'Dễ', MEDIUM: 'Trung bình', HARD: 'Khó', MANUAL: 'Thủ công', IMPORT: 'Import CSV' }
function Badge({ status }: { status: string }) { return <span className={`badge ${status === 'APPROVED' ? 'badge-green' : status === 'REJECTED' ? 'badge-yellow' : 'badge-gray'}`}>{labels[status] ?? status}</span> }
function QuestionContent({ question }: { question: Question }) {
  return <div>
    <p style={{ whiteSpace: 'pre-wrap' }}>{question.content}</p>
    <ol type="A">{question.options.map(o => <li key={o.id}>{o.content}{o.correct === true && <strong> ✓ Đúng</strong>}</li>)}</ol>
    {question.expectedAnswer && <p style={{ whiteSpace: 'pre-wrap' }}><strong>Đáp án / hướng dẫn chấm:</strong> {question.expectedAnswer}</p>}
    <p>Phương thức: {labels[question.creationMethod] ?? question.creationMethod} · Nguồn: {question.sourceType}</p>
    {question.sourceLabel && <p>{question.sourceLabel}</p>}
    {question.sourceRefId && <p>Tham chiếu: {question.sourceRefId}</p>}
    {question.sourceUrl && <p>{/^https?:\/\//i.test(question.sourceUrl) ? <a href={question.sourceUrl} target="_blank" rel="noreferrer">{question.sourceUrl}</a> : question.sourceUrl}</p>}
    {question.sourceExcerpt && <blockquote style={{ whiteSpace: 'pre-wrap' }}>{question.sourceExcerpt}</blockquote>}
  </div>
}
export default function QuestionListPage() {
  const qc = useQueryClient(), user = useAuthStore(s => s.user)
  const fileRef = useRef<HTMLInputElement>(null)
  const [subjectId, setSubjectId] = useState(''), [filters, setFilters] = useState<Filters>({ page: 0 })
  const [selectedId, setSelectedId] = useState(''), [modal, setModal] = useState<'create' | 'edit' | null>(null)
  const [reviewNote, setReviewNote] = useState(''), [message, setMessage] = useState('')
  const [importCategory, setImportCategory] = useState(''), [importResult, setImportResult] = useState<ImportResult | null>(null)
  const author = user?.role === 'ADMIN' || user?.role === 'TEACHER'
  const subjects = useQuery({ queryKey: ['subjects'], queryFn: questionBankApi.getSubjects })
  const categories = useQuery({ queryKey: ['categories', subjectId], queryFn: () => questionBankApi.getCategories(subjectId), enabled: !!subjectId })
  const list = useQuery({ queryKey: ['questions', user?.id, filters], queryFn: () => questionBankApi.getQuestions(filters) })
  const detail = useQuery({ queryKey: ['question', user?.id, author, selectedId], queryFn: () => questionBankApi.getQuestion(selectedId, author), enabled: !!selectedId })
  const versions = useQuery({ queryKey: ['versions', user?.id, author, selectedId, detail.data?.questionVersion], queryFn: () => questionBankApi.getVersions(selectedId, author), enabled: !!detail.data })
  const reviews = useQuery({ queryKey: ['reviews', user?.id, selectedId, detail.data?.status], queryFn: () => questionBankApi.getReviews(selectedId), enabled: !!detail.data })
  const q = detail.data, owns = !!q && author && q.createdBy === user?.id
  const action = useMutation({
    mutationFn: async (kind: 'submit' | 'approve' | 'reject' | 'archive') => {
      if (!q) return
      if (kind === 'submit') return questionBankApi.submitReview(q)
      if (kind === 'archive') { await questionBankApi.archive(q); return }
      return questionBankApi.review(q, kind === 'approve' ? 'APPROVED' : 'REJECTED', reviewNote)
    },
    onSuccess: async () => { setMessage('Đã cập nhật trạng thái.'); setReviewNote(''); await qc.invalidateQueries({ queryKey: ['questions'] }); await qc.invalidateQueries({ queryKey: ['question'] }); await qc.invalidateQueries({ queryKey: ['reviews'] }) },
    onError: e => setMessage(apiError(e)),
  })
  const importMut = useMutation({
    mutationFn: (file: File) => questionBankApi.importCsv(file, importCategory),
    onSuccess: async r => { setImportResult(r); setMessage('Import hoàn tất; các câu hỏi mới ở DRAFT.'); await qc.invalidateQueries({ queryKey: ['questions'] }) },
    onError: e => setMessage(apiError(e)),
  })
  function filter(key: keyof Filters, value: string) { setFilters(f => ({ ...f, [key]: value || undefined, page: 0 })) }
  return <div style={{ display: 'grid', gap: 20 }}>
    <header style={{ display: 'flex', justifyContent: 'space-between', gap: 16, flexWrap: 'wrap' }}>
      <div><h1>Ngân hàng câu hỏi</h1><p>{list.data?.totalElements ?? 0} câu hỏi · Chỉ câu hỏi đã duyệt được dùng trong Exam.</p></div>
      {author && <button className="btn-primary" onClick={() => setModal('create')}>Thêm câu hỏi</button>}
    </header>
    <section className="clean-card" aria-label="Bộ lọc" style={{ padding: 16, display: 'flex', gap: 12, flexWrap: 'wrap' }}>
      <label>Môn học<select className="input-field" value={subjectId} onChange={e => { setSubjectId(e.target.value); filter('categoryId', ''); setImportCategory('') }}><option value="">Chọn môn học</option>{subjects.data?.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}</select></label>
      <label>Danh mục<select className="input-field" value={filters.categoryId ?? ''} disabled={!subjectId} onChange={e => filter('categoryId', e.target.value)}><option value="">Tất cả</option>{categories.data?.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</select></label>
      {([['difficulty', 'Độ khó', ['EASY','MEDIUM','HARD']], ['questionType', 'Loại câu hỏi', types], ['status', 'Trạng thái', statuses], ['creationMethod', 'Phương thức', methods], ['sourceType', 'Loại nguồn', sources]] as [keyof Filters, string, string[]][]).map(([key, label, values]) =>
        <label key={key}>{label}<select className="input-field" value={String(filters[key] ?? '')} onChange={e => filter(key, e.target.value)}><option value="">Tất cả</option>{values.map(v => <option key={v} value={v}>{labels[v] ?? v}</option>)}</select></label>)}
      <label>Tìm nội dung<input className="input-field" type="search" maxLength={200} value={filters.text ?? ''} onChange={e => filter('text', e.target.value)}/></label>
    </section>
    {author && <section className="clean-card" aria-label="Import CSV" style={{ padding: 16 }}>
      <label>Danh mục import<select className="input-field" value={importCategory} onChange={e => setImportCategory(e.target.value)}><option value="">Chọn danh mục</option>{categories.data?.map(c => <option key={c.id} value={c.id}>{c.name}</option>)}</select></label>
      <input ref={fileRef} type="file" aria-label="CSV" accept=".csv" hidden onChange={e => { const file = e.target.files?.[0]; if (file) { setImportResult(null); importMut.mutate(file) } e.target.value = '' }}/>
      <button className="btn-secondary" disabled={!importCategory || importMut.isPending} onClick={() => fileRef.current?.click()}>{importMut.isPending ? 'Đang import…' : 'Import CSV'}</button>
      <p>Header: content,type,difficulty,source,option1,correct1,option2,correct2 (true/false). Trả lời ngắn/tự luận dùng expectedAnswer; có thể thêm sourceType,sourceRefId,sourceLabel,sourceUrl,sourceExcerpt.</p>
      {importResult && <div role="status"><p>{importResult.imported}/{importResult.totalRows} thành công · {importResult.failed} lỗi</p><ul>{importResult.errors.map((e, i) => <li key={i}>{e}</li>)}</ul></div>}
    </section>}
    {message && <p role="status">{message}</p>}
    {(subjects.isError || categories.isError) && <p role="alert">Không tải được môn học/danh mục.</p>}
    <div style={{ display: 'flex', gap: 20, flexWrap: 'wrap', alignItems: 'flex-start' }}>
      <section className="clean-card" aria-label="Danh sách câu hỏi" style={{ flex: '1 1 300px', padding: 16, display: 'grid', gap: 12 }}>
        {list.isLoading && <p>Đang tải…</p>}{list.isError && <p role="alert">{apiError(list.error)}</p>}
        {!list.isLoading && list.data?.content.length === 0 && <p>Không tìm thấy câu hỏi.</p>}
        {list.data?.content.map(item => <button key={item.id} className="btn-secondary" aria-pressed={selectedId === item.id} style={{ display: 'block', textAlign: 'left', whiteSpace: 'normal', padding: 16 }} onClick={() => { setSelectedId(item.id); setReviewNote(''); setMessage('') }}>
          <Badge status={item.status}/> <span>{labels[item.questionType]} · {labels[item.difficultyLevel]} · v{item.questionVersion}</span><p>{item.content}</p>
        </button>)}
        <nav aria-label="Phân trang" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <button className="btn-secondary" disabled={(filters.page ?? 0) === 0} onClick={() => setFilters(f => ({ ...f, page: (f.page ?? 0) - 1 }))}>Trước</button>
          <span>{(filters.page ?? 0) + 1} / {Math.max(1, list.data?.totalPages ?? 1)}</span>
          <button className="btn-secondary" disabled={(filters.page ?? 0) + 1 >= (list.data?.totalPages ?? 0)} onClick={() => setFilters(f => ({ ...f, page: (f.page ?? 0) + 1 }))}>Sau</button>
        </nav>
      </section>
      <section className="clean-card" aria-label="Chi tiết câu hỏi" style={{ flex: '2 1 420px', padding: 24, minWidth: 0 }}>
        {detail.isLoading && <p>Đang tải chi tiết…</p>}{detail.isError && <p role="alert">{apiError(detail.error)}</p>}
        {!selectedId && <p>Chọn một câu hỏi để xem chi tiết.</p>}
        {q && <><h2>Chi tiết câu hỏi · v{q.questionVersion}</h2><Badge status={q.status}/><p>{q.categoryName} · PERSONAL · cập nhật {new Date(q.updatedAt).toLocaleString('vi-VN')}</p>
          <QuestionContent question={q}/>
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 12 }}>
            {owns && ['DRAFT','REJECTED','APPROVED'].includes(q.status) && <button className="btn-secondary" disabled={action.isPending} onClick={() => setModal('edit')}>Sửa / tạo phiên bản mới</button>}
            {owns && q.status === 'DRAFT' && <button className="btn-primary" disabled={action.isPending} onClick={() => action.mutate('submit')}>Gửi duyệt</button>}
            {owns && q.status !== 'ARCHIVED' && <button className="btn-secondary" disabled={action.isPending} onClick={() => { if (confirm('Lưu trữ câu hỏi? Câu hỏi sẽ không còn đủ điều kiện cho Exam.')) action.mutate('archive') }}>Lưu trữ</button>}
          </div>
          {user?.role === 'ADMIN' && q.status === 'PENDING_REVIEW' && <div style={{ display: 'grid', gap: 12, marginTop: 16 }}>
            <label>Ghi chú duyệt<textarea className="input-field" maxLength={5000} value={reviewNote} onChange={e => setReviewNote(e.target.value)}/></label>
            <div style={{ display: 'flex', gap: 12 }}><button className="btn-primary" disabled={action.isPending} onClick={() => action.mutate('approve')}>Phê duyệt</button><button className="btn-secondary" disabled={action.isPending || !reviewNote.trim()} onClick={() => action.mutate('reject')}>Từ chối</button></div>
          </div>}
          <h3>Lịch sử duyệt</h3>{reviews.isError && <p role="alert">Không tải được lịch sử duyệt.</p>}
          {reviews.data?.length === 0 && <p>Chưa có quyết định duyệt.</p>}
          {reviews.data?.map(r => <p key={r.id}>v{r.questionVersion} · {labels[r.decision]} · {new Date(r.reviewedAt).toLocaleString('vi-VN')} · người duyệt {r.reviewerId}<br/>{r.reviewNote}</p>)}
          <h3>Lịch sử phiên bản</h3>{versions.isError && <p role="alert">Không tải được lịch sử phiên bản.</p>}
          {versions.data?.map(v => <details key={v.versionNumber}><summary>v{v.versionNumber} · {new Date(v.createdAt).toLocaleString('vi-VN')} · người sửa {v.changedBy}</summary><QuestionContent question={v.question}/></details>)}
        </>}
      </section>
    </div>
    {modal && <QuestionModal categories={categories.data ?? []} initialData={modal === 'edit' ? q : null} onClose={() => setModal(null)}/>}
  </div>
}

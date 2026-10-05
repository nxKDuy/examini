import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { FilePlus2, MailCheck } from 'lucide-react'
import { examApi, type ExamStatus } from './examApi'
import { useAuthStore } from '../../store/authStore'
import { ExamStatusBadge } from '../../components/status'
import { EmptyState, ErrorState, LoadingState, Notice, PageHeader, Pagination } from '../../components/ui'
import { errorText } from '../../lib/errors'
import { ACCESS_MODE_LABEL, formatDateTime, formatScore } from '../../lib/format'

const FILTERS: { value: '' | ExamStatus; label: string }[] = [
  { value: '', label: 'Tất cả' }, { value: 'DRAFT', label: 'Bản nháp' }, { value: 'PUBLISHED', label: 'Đã xuất bản' },
  { value: 'OPEN', label: 'Đang mở' }, { value: 'CLOSED', label: 'Đã đóng' },
]

export default function ExamListPage() {
  const qc = useQueryClient()
  const navigate = useNavigate()
  const user = useAuthStore(s => s.user)
  const [page, setPage] = useState(0)
  const [status, setStatus] = useState<'' | ExamStatus>('')
  const list = useQuery({ queryKey: ['exams', 'list', page], queryFn: () => examApi.list(page), placeholderData: prev => prev })
  const invites = useQuery({ queryKey: ['invitations'], queryFn: examApi.invitations })
  const accept = useMutation({
    mutationFn: examApi.accept,
    onSuccess: async () => { await qc.invalidateQueries({ queryKey: ['invitations'] }); await qc.invalidateQueries({ queryKey: ['exams'] }) },
  })
  const exams = (list.data?.content ?? []).filter(e => !status || e.status === status)

  return (
    <div className="stack">
      <PageHeader title="Đề thi" description="Đề thi bạn sở hữu hoặc được mời cộng tác. Soạn bản nháp, xuất bản để cố định nội dung, rồi mở cho thí sinh."
        actions={<Link to="/exams/create" className="btn btn-primary"><FilePlus2 size={16} aria-hidden /> Tạo đề thi</Link>} />

      {accept.isError && <Notice tone="danger" live>{errorText(accept.error)}</Notice>}
      {(invites.data?.length ?? 0) > 0 && (
        <section className="card" aria-label="Lời mời cộng tác">
          <div className="card-header"><h2 className="row"><MailCheck size={18} aria-hidden /> Lời mời cộng tác</h2></div>
          {invites.data!.map(e => (
            <div key={e.id} className="spread" style={{ padding: '12px 20px', borderBottom: '1px solid var(--color-border)' }}>
              <div><strong style={{ color: 'var(--color-ink)' }}>{e.title}</strong> <ExamStatusBadge status={e.status} /></div>
              <button type="button" className="btn btn-primary btn-sm" disabled={accept.isPending} onClick={() => accept.mutate(e.id)}>Chấp nhận</button>
            </div>
          ))}
        </section>
      )}

      <section className="card" style={{ overflow: 'hidden' }}>
        <div className="tabs" role="tablist" aria-label="Lọc theo trạng thái" style={{ padding: '0 12px' }}>
          {FILTERS.map(f => (
            <button key={f.value} type="button" role="tab" className="tab" aria-selected={status === f.value} onClick={() => setStatus(f.value)}>
              {f.label}{list.data && <span className="count">{f.value ? list.data.content.filter(e => e.status === f.value).length : list.data.content.length}</span>}
            </button>
          ))}
        </div>
        {list.isLoading ? <LoadingState rows={5} /> : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} />
          : exams.length === 0 ? <EmptyState icon={<FilePlus2 size={30} />} title={status ? 'Không có đề thi ở trạng thái này' : 'Chưa có đề thi'}
              description="Tạo đề thi từ các câu hỏi đã được duyệt trong Ngân hàng câu hỏi."
              action={!status && <Link to="/exams/create" className="btn btn-primary btn-sm">Tạo đề thi</Link>} />
          : <div className="table-wrap"><table className="table">
              <thead><tr>
                <th scope="col">Đề thi</th><th scope="col">Trạng thái</th><th scope="col">Vai trò</th><th scope="col">Khung giờ</th>
                <th scope="col">Tham gia</th><th scope="col" style={{ textAlign: 'right' }}>Câu / điểm</th>
              </tr></thead>
              <tbody>{exams.map(e => (
                <tr key={e.id} className="clickable" onClick={() => navigate(`/exams/${e.id}`)}>
                  <td style={{ minWidth: 220 }}>
                    <Link to={`/exams/${e.id}`} onClick={ev => ev.stopPropagation()} style={{ fontWeight: 600, color: 'var(--color-ink)', textDecoration: 'none' }}>{e.title}</Link>
                    <div className="xsmall muted">{e.durationMinutes} phút · Mã <span className="mono">{e.code}</span></div>
                  </td>
                  <td><ExamStatusBadge status={e.status} /></td>
                  <td className="small">{e.ownerUserId === user?.id ? 'Chủ sở hữu' : 'Cộng tác'}</td>
                  <td className="xsmall" style={{ minWidth: 170 }}>{e.startTime || e.endTime ? <>{formatDateTime(e.startTime, 'Không giới hạn')}<br />đến {formatDateTime(e.endTime, 'không giới hạn')}</> : <span className="muted">Không giới hạn</span>}</td>
                  <td className="small">{ACCESS_MODE_LABEL[e.accessMode]}</td>
                  <td className="mono small" style={{ textAlign: 'right' }}>{e.questionIds.length} / {formatScore(e.totalScore)}</td>
                </tr>
              ))}</tbody>
            </table></div>}
        {list.data && <Pagination label="Phân trang đề thi" page={page} totalPages={list.data.totalPages} onChange={setPage} />}
        {status && list.data && list.data.totalPages > 1 && <p className="xsmall muted" style={{ padding: '0 20px 12px' }}>Bộ lọc trạng thái áp dụng trên trang hiện tại.</p>}
      </section>
    </div>
  )
}

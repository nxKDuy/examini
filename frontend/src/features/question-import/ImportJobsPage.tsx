import { useState } from 'react'
import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { FileUp } from 'lucide-react'
import { importApi, type JobStatus } from './importApi'
import ImportUploadDialog from './ImportUploadDialog'
import { Badge, EmptyState, ErrorState, LoadingState, PageHeader, Pagination } from '../../components/ui'
import { formatDateTime } from '../../lib/format'

const STATUS: Record<JobStatus, { label: string; tone: 'neutral' | 'success' | 'danger' | 'muted' | 'warning' }> = {
  PROCESSING: { label: 'Đang xử lý', tone: 'warning' }, READY: { label: 'Chờ xem lại', tone: 'neutral' }, FAILED: { label: 'Lỗi', tone: 'danger' }, DISCARDED: { label: 'Đã hủy', tone: 'muted' },
}

/** The uploader's own import jobs, newest first. */
export default function ImportJobsPage() {
  const [page, setPage] = useState(0)
  const [uploading, setUploading] = useState(false)
  const jobs = useQuery({ queryKey: ['import-jobs', page], queryFn: () => importApi.jobs(page), refetchInterval: q => q.state.data?.content.some(j => j.status === 'PROCESSING') ? 2000 : false })
  return (
    <div className="stack-lg">
      <PageHeader title="Nhập tài liệu" crumbs={[{ label: 'Kho cá nhân', to: '/questions' }, { label: 'Nhập tài liệu' }]}
        description="Tải DOCX, PDF hoặc ảnh; xem lại từng câu nhận dạng được rồi nhập vào Kho cá nhân dưới dạng bản nháp."
        actions={<button type="button" className="btn btn-primary" onClick={() => setUploading(true)}><FileUp size={16} aria-hidden /> Tải tài liệu</button>} />
      <section className="card" style={{ overflow: 'hidden' }}>
        {jobs.isLoading ? <LoadingState rows={3} /> : jobs.isError ? <ErrorState error={jobs.error} onRetry={() => void jobs.refetch()} />
          : !jobs.data?.content.length ? <EmptyState title="Chưa có lô nhập nào" description="Bấm Tải tài liệu để bắt đầu." />
          : <div className="table-wrap"><table className="table">
            <thead><tr><th scope="col">Tệp</th><th scope="col">Trạng thái</th><th scope="col">Câu</th><th scope="col">Tải lên</th></tr></thead>
            <tbody>{jobs.data.content.map(j => <tr key={j.id}>
              <td><Link to={`/questions/imports/${j.id}`}>{j.fileName}</Link><div className="xsmall muted">{j.fileKind} · {j.pageCount} trang{j.ocrPageCount ? ` · ${j.ocrPageCount} OCR` : ''}</div></td>
              <td><Badge tone={STATUS[j.status].tone}>{STATUS[j.status].label}</Badge></td>
              <td className="small">{j.summary.total} câu · {j.summary.importable} sẵn sàng · {j.summary.imported} đã nhập</td>
              <td className="small">{formatDateTime(j.createdAt)}</td>
            </tr>)}</tbody>
          </table></div>}
        {jobs.data && jobs.data.totalPages > 1 && <Pagination label="Phân trang lô nhập" page={page} totalPages={jobs.data.totalPages} onChange={setPage} />}
      </section>
      {uploading && <ImportUploadDialog onClose={() => setUploading(false)} />}
    </div>
  )
}

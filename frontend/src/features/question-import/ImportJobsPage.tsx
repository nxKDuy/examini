import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { FileUp, Sparkles } from 'lucide-react'
import { importApi, type JobStatus } from './importApi'
import ImportUploadDialog from './ImportUploadDialog'
import AiGenerateDialog from './AiGenerateDialog'
import { Badge, EmptyState, ErrorState, LoadingState, PageHeader, Pagination } from '../../components/ui'
import { formatDateTime } from '../../lib/format'
import { t, useT, i18nMap } from '../../lib/i18n'

const STATUS: Record<JobStatus, { label: string; tone: 'neutral' | 'success' | 'danger' | 'muted' | 'warning' }> = i18nMap({
  PROCESSING: { label: 'Đang xử lý', tone: 'warning' }, READY: { label: 'Chờ xem lại', tone: 'neutral' }, FAILED: { label: 'Lỗi', tone: 'danger' }, DISCARDED: { label: 'Đã hủy', tone: 'muted' },
})

/** The uploader's own import jobs, newest first. */
export default function ImportJobsPage() {
  useT()
  const [page, setPage] = useState(0)
  const [uploading, setUploading] = useState(false)
  // ?new=ai (from the bank toolbar) opens the AI dialog directly.
  const [params, setParams] = useSearchParams()
  const [generating, setGenerating] = useState(params.get('new') === 'ai')
  const closeAi = () => { setGenerating(false); if (params.has('new')) setParams({}, { replace: true }) }
  const jobs = useQuery({ queryKey: ['import-jobs', page], queryFn: () => importApi.jobs(page), refetchInterval: q => q.state.data?.content.some(j => j.status === 'PROCESSING') ? 2000 : false })
  return (
    <div className="stack-lg">
      <PageHeader title={t('Nhập tài liệu')} crumbs={[{ label: t('Kho cá nhân'), to: '/questions' }, { label: t('Nhập tài liệu') }]}
        description={t('Tải DOCX, PDF hoặc ảnh, hoặc nhờ AI soạn câu hỏi; xem lại từng câu rồi nhập vào Kho cá nhân dưới dạng bản nháp.')}
        actions={<>
          <button type="button" className="btn btn-secondary" onClick={() => setGenerating(true)}><Sparkles size={16} aria-hidden /> {t('Tạo bằng AI')}</button>
          <button type="button" className="btn btn-primary" onClick={() => setUploading(true)}><FileUp size={16} aria-hidden /> {t('Tải tài liệu')}</button>
        </>} />
      <section className="card" style={{ overflow: 'hidden' }}>
        {jobs.isLoading ? <LoadingState rows={3} /> : jobs.isError ? <ErrorState error={jobs.error} onRetry={() => void jobs.refetch()} />
          : !jobs.data?.content.length ? <EmptyState title={t('Chưa có lô nhập nào')} description={t('Bấm Tải tài liệu để bắt đầu.')} />
          : <div className="table-wrap"><table className="table">
            <thead><tr><th scope="col">{t('Tệp')}</th><th scope="col">{t('Trạng thái')}</th><th scope="col">{t('Câu')}</th><th scope="col">{t('Tải lên')}</th></tr></thead>
            <tbody>{jobs.data.content.map(j => <tr key={j.id}>
              <td><Link to={`/questions/imports/${j.id}`}>{j.fileName}</Link><div className="xsmall muted">{j.fileKind === 'AI' ? t('AI · yêu cầu {0} câu', { 0: j.generation?.requestedCount ?? '?' }) : <>{j.fileKind} · {t('{0} trang', { 0: j.pageCount })}{j.ocrPageCount ? ` · ${j.ocrPageCount} OCR` : ''}</>}</div></td>
              <td><Badge tone={STATUS[j.status].tone}>{STATUS[j.status].label}</Badge></td>
              <td className="small">{j.summary.total} {t('câu ·')} {j.summary.importable} {t('sẵn sàng ·')} {j.summary.imported} {t('đã nhập')}</td>
              <td className="small">{formatDateTime(j.createdAt)}</td>
            </tr>)}</tbody>
          </table></div>}
        {jobs.data && jobs.data.totalPages > 1 && <Pagination label={t('Phân trang lô nhập')} page={page} totalPages={jobs.data.totalPages} onChange={setPage} />}
      </section>
      {uploading && <ImportUploadDialog onClose={() => setUploading(false)} />}
      {generating && <AiGenerateDialog onClose={closeAi} />}
    </div>
  )
}

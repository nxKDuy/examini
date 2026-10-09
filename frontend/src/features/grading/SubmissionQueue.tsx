import { ClipboardCheck } from 'lucide-react'
import type { Page, Submission } from './gradingApi'
import { publicationOf, studentName } from './gradingModel'
import { ResultStatusBadge } from '../../components/status'
import { Badge, EmptyState, ErrorState, LoadingState, Pagination } from '../../components/ui'
import { formatDateTime, formatScore } from '../../lib/format'

/** Graded and published are different facts: GRADED is the Teacher's finalize, publication is what the Student can see. */
export function PublicationBadge({ s }: { s: Pick<Submission, 'publishedRevision' | 'resultRevision'> }) {
  const p = publicationOf(s)
  if (p === 'CURRENT') return <Badge tone="success">Đã công bố</Badge>
  if (p === 'OUTDATED') return <Badge tone="info" title="Học sinh vẫn thấy bản công bố trước">Bản cũ đang hiển thị</Badge>
  return <Badge tone="muted">Chưa công bố</Badge>
}

export default function SubmissionQueue({ data, loading, error, onRetry, activeId, onSelect, page, onPage, status }: {
  data: Page<Submission> | undefined; loading: boolean; error: unknown; onRetry: () => void
  activeId: string | null; onSelect: (id: string) => void; page: number; onPage: (p: number) => void; status: string
}) {
  return (
    <section className="card grade-queue" aria-label="Danh sách bài nộp">
      <div className="card-header"><h2 style={{ fontSize: '0.9375rem' }}>{data ? `${data.totalElements} bài nộp` : 'Bài nộp'}</h2></div>
      {loading ? <LoadingState rows={4} />
        : error ? <ErrorState error={error} onRetry={onRetry} />
        : !data || data.content.length === 0 ? <EmptyState icon={<ClipboardCheck size={26} />} title="Không có bài nộp" description={status ? 'Không có bài khớp bộ lọc này.' : 'Bài nộp xuất hiện khi thí sinh nộp bài.'} />
        : data.content.map(s => (
          <button key={s.id} type="button" className="list-item" aria-current={activeId === s.id} onClick={() => onSelect(s.id)}>
            <div className="spread">
              <span className="small" style={{ color: 'var(--color-ink)' }} title={s.studentId}>{studentName(s)} · #{s.attemptNumber}</span>
              <ResultStatusBadge status={s.status} />
            </div>
            <div className="row" style={{ marginTop: 6, gap: 6 }}>
              <PublicationBadge s={s} />
              {s.invalidated && <Badge tone="danger" title={s.invalidationReason ?? undefined}>Đã vô hiệu</Badge>}
              {s.pendingManualItemCount > 0 && <Badge tone="warning">{s.pendingManualItemCount} câu chờ</Badge>}
            </div>
            <div className="spread xsmall muted" style={{ marginTop: 6 }}>
              <span>Nộp {formatDateTime(s.submittedAt, '-')}</span>
              <span className="mono">{s.status === 'GRADED' ? `${formatScore(s.score)}/${formatScore(s.totalScore)}` : `-/${formatScore(s.totalScore)}`}</span>
            </div>
            {s.publishedScore !== null && s.publishedRevision !== s.resultRevision && <div className="xsmall muted">Học sinh thấy: {formatScore(s.publishedScore)}/{formatScore(s.totalScore)}</div>}
          </button>
        ))}
      {data && <Pagination label="Phân trang bài nộp" page={page} totalPages={data.totalPages} onChange={onPage} />}
    </section>
  )
}

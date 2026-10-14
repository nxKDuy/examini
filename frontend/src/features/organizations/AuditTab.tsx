import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { ScrollText } from 'lucide-react'
import { orgApi, type AuditEntry, type Organization } from './orgApi'
import { AUDIT_LABEL, shortId } from './orgModel'
import { EmptyState, ErrorState, LoadingState, Pagination } from '../../components/ui'
import { formatDateTime } from '../../lib/format'
import { t, useT } from '../../lib/i18n'

const UUID_IN = /[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/i
/** Audit detail like "MEMBER->ADMIN" is useful; raw ids are not, so those are not printed. */
const readableDetail = (e: AuditEntry) => (e.detail && !UUID_IN.test(e.detail) && e.detail.length <= 60 ? e.detail : null)

export default function AuditTab({ org }: { org: Organization }) {
  useT()
  const [page, setPage] = useState(0)
  const list = useQuery({ queryKey: ['organizations', 'audit', org.id, page], queryFn: () => orgApi.audit(org.id, { page }), placeholderData: prev => prev })
  return (
    <section className="card" aria-label={t('Nhật ký thay đổi')} style={{ overflow: 'hidden' }}>
      {list.isLoading ? <LoadingState rows={4} />
        : list.isError ? <ErrorState error={list.error} onRetry={() => void list.refetch()} />
        : (list.data?.items.length ?? 0) === 0 ? <EmptyState icon={<ScrollText size={28} />} title={t('Chưa có nhật ký')} description={t('Các thay đổi về thành viên, lời mời và quyền sẽ được ghi ở đây.')} />
        : <ol className="org-audit">
            {list.data!.items.map(e => (
              <li key={e.id}>
                <strong>{AUDIT_LABEL[e.action] ?? e.action}</strong>
                <span className="xsmall muted">{formatDateTime(e.occurredAt, '-')}{e.actorUserId && <> {t('· bởi')} <span className="org-id">{shortId(e.actorUserId)}</span></>}{e.targetUserId && <> {t('· đối tượng')} <span className="org-id">{shortId(e.targetUserId)}</span></>}{readableDetail(e) && <> · {readableDetail(e)}</>}</span>
              </li>
            ))}
          </ol>}
      {list.data && <Pagination label={t('Phân trang nhật ký')} page={page} totalPages={list.data.totalPages} onChange={setPage} />}
    </section>
  )
}

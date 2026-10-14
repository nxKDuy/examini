import { Link } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import './participants.css'
import { participantApi, type Mine } from './participantApi'
import { ATTEMPT_STATUS_LABEL, publicReason, resultLink, resumable } from './participantModel'
import { Badge, ErrorState, Notice, Section } from '../../components/ui'
import { formatDateTime } from '../../lib/format'
import { t, useT } from '../../lib/i18n'

/** "Your attempts" on the Lobby. Own data only: used/remaining/total, each attempt with its result link, and the PUBLIC reason when one was invalidated. */
export default function StudentAttempts({ examId, mine }: { examId: string; mine: Mine }) {
  useT()
  const fallback = useQuery({ queryKey: ['participation', 'attempts', examId], queryFn: () => participantApi.myAttempts(examId), enabled: mine.attempts === undefined })
  const attempts = mine.attempts ?? fallback.data ?? []
  const grants = mine.grants ?? []
  return (
    <Section title={t('Lượt làm bài của bạn')}>
      <div className="stack">
        <dl className="meta">
          <div><dt>{t('Đã dùng')}</dt><dd>{mine.used}</dd></div>
          <div><dt>{t('Còn lại')}</dt><dd>{mine.remaining}</dd></div>
          <div><dt>{t('Tổng được làm')}</dt><dd>{mine.effectiveAttemptLimit}{mine.extraAttempts > 0 ? t(' (gồm {0} lượt giáo viên cấp thêm)', { 0: mine.extraAttempts }) : ''}</dd></div>
        </dl>
        {grants.length > 0 && <ul className="pt-list">{grants.map(g => <li key={g.grantId} className="small muted">{t('Được cấp thêm')} {g.quantity} {t('lượt ·')} {formatDateTime(g.createdAt, '-')}{g.reason ? ` · ${g.reason}` : ''}</li>)}</ul>}
        {fallback.isError && <ErrorState error={fallback.error} onRetry={() => void fallback.refetch()} />}
        {attempts.length === 0 ? <p className="small muted">{t('Bạn chưa làm lượt nào.')}</p>
          : <ul className="pt-list">{[...attempts].sort((a, b) => b.attemptNumber - a.attemptNumber).map(a => {
              const link = resultLink(a)
              return (
                <li key={a.sessionId} className="pt-attempt" data-invalid={a.invalidated}>
                  <span className="spread"><strong>{t('Lượt #')}{a.attemptNumber}</strong>
                    <span className="row" style={{ gap: 6 }}><Badge tone={a.status === 'IN_PROGRESS' ? 'info' : 'neutral'}>{ATTEMPT_STATUS_LABEL[a.status] ?? a.status}</Badge>{a.invalidated && <Badge tone="danger">{t('Đã vô hiệu')}</Badge>}</span></span>
                  <span className="xsmall muted">{t('Bắt đầu')} {formatDateTime(a.startedAt, '-')}{a.endedAt ? t(' · kết thúc {0}', { 0: formatDateTime(a.endedAt, '-') }) : ''}</span>
                  {a.invalidated && <span className="small">{t('Lý do:')} {publicReason(a) ?? t('không ghi')}</span>}
                  <span className="row" style={{ gap: 8 }}>
                    {resumable(a) && <Link className="btn btn-primary btn-sm" to={`/exam/take/${a.sessionId}`}>{t('Tiếp tục làm bài')}</Link>}
                    {link && <Link className="btn btn-secondary btn-sm" to={link}>{t('Xem kết quả')}</Link>}
                  </span>
                </li>
              )
            })}</ul>}
      </div>
    </Section>
  )
}

export function RevokedNotice({ mine }: { mine: Mine }) {
  useT()
  return (
    <Notice tone="warning" title={t('Bạn không còn trong danh sách của kỳ thi này')}>
      {t('Bạn không thể tham gia hoặc bắt đầu lượt mới.')}{mine.revocationReason ? t(' Lý do: {0}.', { 0: mine.revocationReason }) : ''} {mine.activeAttempt ? t('Lượt đang làm vẫn tiếp tục được và nộp theo hạn đã cấp.') : ''}
    </Notice>
  )
}

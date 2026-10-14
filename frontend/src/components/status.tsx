import { CircleDashed } from 'lucide-react'
import { Badge } from './ui'
import { EXAM_STATUS, QUESTION_STATUS, RESULT_STATUS, SESSION_STATUS, type StatusDef } from './statusDefs'
import { t, useT } from '../lib/i18n'

function StatusBadge({ map, status }: { map: Record<string, StatusDef>; status: string | null | undefined }) {
  useT()
  const def = status ? map[status] : undefined
  if (!def) return <Badge tone="neutral" icon={<CircleDashed size={12} aria-hidden />}>{status ?? t('Không rõ')}</Badge>
  return <Badge tone={def.tone} icon={def.icon} title={def.help}>{def.label}</Badge>
}

export const QuestionStatusBadge = ({ status }: { status: string }) => <StatusBadge map={QUESTION_STATUS} status={status} />
export const ExamStatusBadge = ({ status }: { status: string }) => <StatusBadge map={EXAM_STATUS} status={status} />
export const SessionStatusBadge = ({ status }: { status: string }) => <StatusBadge map={SESSION_STATUS} status={status} />
export const ResultStatusBadge = ({ status }: { status: string }) => <StatusBadge map={RESULT_STATUS} status={status} />

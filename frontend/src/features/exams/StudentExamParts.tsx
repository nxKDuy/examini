import { CalendarClock, CheckCircle2, Lock, PlayCircle, Timer } from 'lucide-react'
import { Badge } from '../../components/ui'
import { availability, type Exam } from './examApi'
import { formatDateTime } from '../../lib/format'

export function AvailabilityBadge({ exam }: { exam: Exam }) {
  const state = availability(exam)
  if (state === 'OPEN') return <Badge tone="success" icon={<PlayCircle size={12} aria-hidden />}>Đang mở</Badge>
  if (state === 'UPCOMING') return <Badge tone="info" icon={<CalendarClock size={12} aria-hidden />}>Mở lúc {formatDateTime(exam.startTime)}</Badge>
  if (state === 'ENDED') return <Badge tone="muted" icon={<Timer size={12} aria-hidden />}>Hết khung giờ</Badge>
  if (state === 'CLOSED') return <Badge tone="muted" icon={<Lock size={12} aria-hidden />}>Đã đóng</Badge>
  return <Badge tone="neutral" icon={<CalendarClock size={12} aria-hidden />}>Chưa mở</Badge>
}

export const AttemptBadge = ({ active }: { active: boolean }) => active
  ? <Badge tone="info" icon={<PlayCircle size={12} aria-hidden />}>Có lượt đang làm</Badge>
  : <Badge tone="success" icon={<CheckCircle2 size={12} aria-hidden />}>Đã nộp lượt gần nhất</Badge>

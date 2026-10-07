import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { Dialog } from '../../components/Dialog'
import { EmptyState, ErrorState, Field, LoadingState } from '../../components/ui'
import { examApi } from '../exams/examApi'
import { EXAM_STATUS } from '../../components/statusDefs'
import ScheduleDialog from './ScheduleDialog'

/** "Đặt lịch cho đề": choose one of your Exams, then open the same schedule form used everywhere else. */
export default function ScheduleExamPicker({ onClose }: { onClose: () => void }) {
  const exams = useQuery({ queryKey: ['exams', 'all'], queryFn: examApi.listAll })
  const [pickedId, setPickedId] = useState('')
  const editable = (exams.data ?? []).filter(e => ['DRAFT', 'PUBLISHED', 'OPEN'].includes(e.status))
  const picked = editable.find(e => e.id === pickedId)
  if (picked) return <ScheduleDialog examId={picked.id} examTitle={picked.title} status={picked.status} durationMinutes={picked.durationMinutes} onClose={onClose} />
  return (
    <Dialog title="Đặt lịch cho đề thi" onClose={onClose} description="Chọn đề thi bạn quản lý. Đề đã đóng không chỉnh được lịch.">
      {exams.isLoading ? <LoadingState rows={3} /> : exams.isError ? <ErrorState error={exams.error} onRetry={() => void exams.refetch()} />
        : editable.length === 0 ? <EmptyState title="Chưa có đề thi để đặt lịch" description="Tạo đề thi ở mục Đề thi, sau đó quay lại đây hoặc dùng tab Lịch trong trang đề." />
        : (
          <Field label="Đề thi" htmlFor="pick-exam">
            <select id="pick-exam" className="input" value={pickedId} onChange={e => setPickedId(e.target.value)} data-autofocus>
              <option value="">Chọn đề thi</option>
              {editable.map(e => <option key={e.id} value={e.id}>{e.title} ({EXAM_STATUS[e.status]?.label ?? e.status}){e.startTime || e.endTime ? ' · đã có lịch' : ''}</option>)}
            </select>
          </Field>
        )}
    </Dialog>
  )
}

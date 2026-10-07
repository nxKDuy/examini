import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Bell, BellOff } from 'lucide-react'
import { calendarApi } from './calendarApi'
import { errorText } from '../../lib/errors'

/** PUBLIC Exam follow: adds it to My Calendar and to notifications. It never joins the Exam or grants Start. */
export default function FollowButton({ examId, followed, size = 'sm', onError, onChanged }: {
  examId: string; followed: boolean; size?: 'sm' | 'md'; onError?: (message: string) => void; onChanged?: (followed: boolean) => void
}) {
  const qc = useQueryClient()
  const m = useMutation({
    mutationFn: () => (followed ? calendarApi.unfollow(examId) : calendarApi.follow(examId)),
    onSuccess: async r => {
      onChanged?.(r.followed)
      await Promise.all(['calendar', 'my-exams', 'public-exams', 'availability'].map(k => qc.invalidateQueries({ queryKey: [k] })))
    },
    onError: e => onError?.(errorText(e)),
  })
  const Icon = followed ? BellOff : Bell
  return (
    <button type="button" className={`btn ${size === 'sm' ? 'btn-sm' : ''} ${followed ? 'btn-secondary' : 'btn-primary'}`} aria-pressed={followed} disabled={m.isPending} onClick={() => m.mutate()}
      title={followed ? 'Bỏ theo dõi: gỡ khỏi Lịch và ngừng nhận thông báo (nếu bạn chưa được giao/tham gia)' : 'Theo dõi: thêm vào Lịch của tôi và nhận thông báo; không tự tham gia kỳ thi'}>
      <Icon size={14} aria-hidden /> {m.isPending ? 'Đang lưu…' : followed ? 'Đang theo dõi' : 'Theo dõi'}
    </button>
  )
}

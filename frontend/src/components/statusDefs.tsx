import type { ReactNode } from 'react'
import { Archive, CheckCircle2, Clock, FileEdit, Hourglass, Lock, PlayCircle, Send, XCircle } from 'lucide-react'
import type { Tone } from './ui'

export interface StatusDef { label: string; tone: Tone; icon: ReactNode; help: string }
const icon = (Icon: typeof Clock) => <Icon size={12} aria-hidden />

export const QUESTION_STATUS: Record<string, StatusDef> = {
  DRAFT: { label: 'Bản nháp', tone: 'neutral', icon: icon(FileEdit), help: 'Đang soạn. Kích hoạt khi nội dung hoàn chỉnh; không cần duyệt.' },
  ACTIVE: { label: 'Đang dùng', tone: 'success', icon: icon(CheckCircle2), help: 'Có thể dùng theo quyền của ngân hàng. Sửa tạo bản nháp phiên bản mới; các bản đã xuất bản giữ nguyên.' },
  ARCHIVED: { label: 'Đã lưu trữ', tone: 'muted', icon: icon(Archive), help: 'Không còn dùng được cho đề thi mới.' },
}

export const EXAM_STATUS: Record<string, StatusDef> = {
  DRAFT: { label: 'Bản nháp', tone: 'neutral', icon: icon(FileEdit), help: 'Có thể chỉnh nội dung, câu hỏi, điểm và chính sách tham gia.' },
  PUBLISHED: { label: 'Đã xuất bản', tone: 'info', icon: icon(Lock), help: 'Nội dung và phiên bản câu hỏi đã được cố định. Thí sinh chưa thể bắt đầu cho đến khi mở kỳ thi.' },
  OPEN: { label: 'Đang mở', tone: 'success', icon: icon(PlayCircle), help: 'Thí sinh đủ điều kiện có thể bắt đầu làm bài trong khung giờ cho phép.' },
  CLOSED: { label: 'Đã đóng', tone: 'muted', icon: icon(Lock), help: 'Không nhận lượt làm bài mới. Lượt đang làm vẫn có thể hoàn thành.' },
  COMPLETED: { label: 'Đã kết thúc', tone: 'muted', icon: icon(CheckCircle2), help: 'Kỳ thi đã kết thúc.' },
}

export const SESSION_STATUS: Record<string, StatusDef> = {
  IN_PROGRESS: { label: 'Đang làm', tone: 'info', icon: icon(Clock), help: 'Lượt làm bài đang diễn ra.' },
  SUBMITTED: { label: 'Đã nộp', tone: 'success', icon: icon(Send), help: 'Thí sinh đã nộp bài.' },
  AUTO_SUBMITTED: { label: 'Tự động nộp', tone: 'warning', icon: icon(Clock), help: 'Hết giờ, hệ thống tự nộp bài.' },
  CANCELLED: { label: 'Đã hủy', tone: 'muted', icon: icon(XCircle), help: 'Lượt làm bài đã bị hủy.' },
}

export const RESULT_STATUS: Record<string, StatusDef> = {
  SUBMITTED: { label: 'Đã nộp', tone: 'info', icon: icon(Send), help: 'Bài đã được ghi nhận và đang chờ chấm.' },
  GRADING: { label: 'Đang chấm', tone: 'info', icon: icon(Hourglass), help: 'Hệ thống đang chấm bài.' },
  PENDING: { label: 'Chờ chấm', tone: 'warning', icon: icon(Hourglass), help: 'Bài đang chờ chấm.' },
  NEEDS_REVIEW: { label: 'Cần chấm tay', tone: 'warning', icon: icon(FileEdit), help: 'Có câu cần giáo viên chấm. Điểm chưa được công bố.' },
  GRADED_UNRELEASED: { label: 'Đã chấm, chờ công bố', tone: 'info', icon: icon(Hourglass), help: 'Giáo viên đã chấm xong nhưng chưa công bố kết quả cho bạn.' },
  GRADED: { label: 'Đã chấm', tone: 'success', icon: icon(CheckCircle2), help: 'Đã có điểm chính thức.' },
  IN_PROGRESS: { label: 'Chưa nộp', tone: 'neutral', icon: icon(Clock), help: 'Lượt làm bài chưa được nộp.' },
}

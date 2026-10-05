// Turns backend ProblemDetail/axios errors into actionable Vietnamese messages.
// Backend messages stay authoritative: unknown details are shown verbatim.

interface ErrorShape {
  message?: string
  response?: { status?: number; data?: { detail?: string; message?: string; error?: string; errors?: Record<string, string> } }
}

export interface ApiErrorInfo { status: number; message: string; detail?: string; fields?: Record<string, string> }

// Exam/Grading wrap provider failures in one generic detail; the HTTP status then carries the meaning.
export const UPSTREAM_GENERIC = /^Required service rejected (this )?request or (is )?unavailable$/

const KNOWN: [RegExp, string | ((m: RegExpMatchArray) => string)][] = [
  [/^Exam is not OPEN$/, 'Kỳ thi hiện không mở. Chỉ có thể bắt đầu khi kỳ thi ở trạng thái Đang mở.'],
  [/^Outside exam schedule$/, 'Hiện không nằm trong khung giờ làm bài của kỳ thi.'],
  [/^Attempt limit reached$/, 'Bạn đã dùng hết số lượt làm bài cho kỳ thi này.'],
  [/^Participant capacity full$/, 'Kỳ thi đã đủ số người tham gia tối đa.'],
  [/^Not assigned$/, 'Bạn chưa được phân công vào kỳ thi này. Hãy liên hệ giáo viên phụ trách.'],
  [/^Invalid join code$/, 'Mã tham gia không đúng. Kiểm tra lại mã với giáo viên.'],
  [/^Join required( before Start)?$/, 'Bạn cần tham gia kỳ thi trước khi vào phòng chờ hoặc bắt đầu.'],
  [/^Exam not found$/, 'Không tìm thấy kỳ thi. Có thể mã hoặc đường dẫn không còn đúng.'],
  [/^Exam permission denied$/, 'Bạn không có quyền quản lý kỳ thi này.'],
  [/^Only owner manages collaborators$/, 'Chỉ chủ sở hữu kỳ thi được quản lý cộng tác viên.'],
  [/^Owner cannot be a member$/, 'Chủ sở hữu không thể được thêm làm cộng tác viên.'],
  [/^Member already exists; remove first$/, 'Người dùng này đã là cộng tác viên hoặc đang được mời. Hãy xóa trước khi đổi vai trò.'],
  [/^Member role required$/, 'Chọn vai trò cộng tác.'],
  [/^No invitation$/, 'Lời mời không còn hiệu lực.'],
  [/^Teacher required$/, 'Chức năng này dành cho tài khoản Giáo viên.'],
  [/^Only DRAFT is editable$/, 'Chỉ đề thi ở trạng thái Bản nháp mới được chỉnh sửa.'],
  [/^Invalid lifecycle transition$/, 'Thao tác không hợp lệ với trạng thái hiện tại của kỳ thi. Hãy tải lại trang.'],
  [/^Question is missing, unapproved or unversioned: (.+)$/, m => `Câu hỏi ${m[1].slice(0, 8)}… chưa được duyệt hoặc không còn tồn tại. Chỉ câu hỏi Đã duyệt mới được xuất bản; hãy thay hoặc gỡ câu hỏi này khỏi đề.`],
  [/^Positive points required$/, 'Mỗi câu hỏi cần có điểm lớn hơn 0.'],
  [/^(Invalid points\/composition|Positive points and unique questions required)$/, 'Điểm phải lớn hơn 0 và mỗi câu hỏi chỉ xuất hiện một lần.'],
  [/^Invalid exam composition\/timing$/, 'Đề thi cần 1 đến 200 câu hỏi không trùng lặp, thời lượng hợp lệ và giờ bắt đầu trước giờ kết thúc.'],
  [/^Invalid passing score$/, 'Điểm đạt phải nằm trong khoảng 0 đến tổng điểm của đề.'],
  [/^Invalid objective grading key$/, 'Một câu trắc nghiệm hoặc Đúng/Sai trong đề có đáp án không hợp lệ. Kiểm tra lại câu hỏi trong Ngân hàng.'],
  [/^Missing short-answer key$/, 'Một câu trả lời ngắn thiếu đáp án chuẩn.'],
  [/^Incomplete snapshot$/, 'Một câu hỏi trong đề thiếu nội dung hoặc có loại không hỗ trợ.'],
  [/^(Duplicate provider question|Unexpected provider question set)$/, 'Không đối chiếu được danh sách câu hỏi với Ngân hàng câu hỏi. Kiểm tra câu hỏi trong đề rồi thử lại.'],
  [/^Insufficient approved questions for requested composition$/, 'Không đủ câu hỏi đã duyệt cho cấu trúc đề yêu cầu.'],
  [/^Invalid policy\/capacity; platform limit (\d+)$/, m => `Giới hạn không hợp lệ: các giá trị phải từ 1 trở lên, số phiên đồng thời không vượt quá số người tham gia và tối đa ${m[1]}.`],
  [/^Existing participants exceed new limit$/, 'Số người đã tham gia lớn hơn giới hạn mới.'],
  [/^Join code already used$/, 'Mã tham gia đã được dùng cho kỳ thi khác.'],
  [/^Join code must be/, 'Mã tham gia gồm 4 đến 50 ký tự: chữ, số, gạch dưới hoặc gạch ngang.'],
  [/^Legacy publication has no frozen snapshot; create a new exam$/, 'Đề này được xuất bản theo cách cũ, không có bản chụp câu hỏi. Hãy tạo đề mới.'],
  [/^Published snapshot unavailable$/, 'Đề thi chưa có bản chụp câu hỏi đã xuất bản.'],
  [/^Session ended$/, 'Lượt làm bài đã kết thúc. Câu trả lời không thể thay đổi.'],
  [/^Not your (session|result)$/, 'Lượt làm bài này không thuộc tài khoản của bạn.'],
  [/^Invalid answer length$/, 'Câu trả lời quá dài (tối đa 20.000 ký tự).'],
  [/^Option outside this question$/, 'Lựa chọn không thuộc câu hỏi này. Hãy tải lại bài thi.'],
  [/^Score outside snapshot total$/, 'Điểm phải nằm trong khoảng 0 đến tổng điểm của bài.'],
  [/^Choose an exam$/, 'Chọn một kỳ thi để xem bài nộp.'],
  [/integration not configured$/, 'Hệ thống chưa cấu hình đầy đủ để thực hiện thao tác này. Liên hệ quản trị viên.'],
]

const BY_STATUS: Record<number, string> = {
  400: 'Dữ liệu gửi lên chưa hợp lệ. Kiểm tra lại các trường.',
  401: 'Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.',
  403: 'Bạn không có quyền thực hiện thao tác này.',
  404: 'Không tìm thấy dữ liệu yêu cầu.',
  409: 'Dữ liệu đã thay đổi hoặc xung đột với trạng thái hiện tại. Tải lại rồi thử lại.',
  503: 'Dịch vụ tạm thời không khả dụng. Thử lại sau ít phút.',
}

export function errorInfo(error: unknown): ApiErrorInfo {
  const e = error as ErrorShape
  const status = e?.response?.status ?? 0
  const data = e?.response?.data
  const detail = data?.detail || data?.message
  if (!e?.response) {
    return { status: 0, message: 'Không kết nối được máy chủ. Kiểm tra mạng rồi thử lại.' }
  }
  if (detail && UPSTREAM_GENERIC.test(detail)) {
    return { status, detail, message: status >= 500 ? 'Một dịch vụ liên quan đang không khả dụng. Thử lại sau ít phút.' : BY_STATUS[status] ?? 'Một dịch vụ liên quan đã từ chối yêu cầu.' }
  }
  if (detail) {
    for (const [pattern, text] of KNOWN) {
      const match = detail.match(pattern)
      if (match) return { status, detail, message: typeof text === 'string' ? text : text(match), fields: data?.errors }
    }
    if (status < 500) return { status, detail, message: detail, fields: data?.errors }
  }
  return { status, message: BY_STATUS[status] ?? 'Máy chủ gặp lỗi khi xử lý yêu cầu. Thử lại sau.', fields: data?.errors }
}

export const errorText = (error: unknown) => errorInfo(error).message
export const errorStatus = (error: unknown) => errorInfo(error).status

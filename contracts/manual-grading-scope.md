# Manual Grading + Student Result Review — PRODUCT SCOPE LOCK

Người dùng duyệt toàn bộ đề xuất ngày 2026-10-07 bằng câu “chốt”.
Baseline MAIN: `d146be39c554f80be380a46c1bff1a90cb0ceced`.

## Quyền và phối hợp

- OWNER/MANAGER của Exam được chấm; PROCTOR không xem/chấm kết quả.
- Teacher không có quyền trên Exam không thuộc quyền quản lý. TEACHER != ADMIN.
- Nhiều giáo viên được cùng làm; không bắt buộc claim. Revision/concurrency check
  ngăn ghi đè; thay đổi trên bản cũ trả 409 và yêu cầu tải lại.
- Student chỉ xem kết quả của chính mình, không gọi thao tác quản lý grading.

## Điểm, feedback và finalize

- Chấm từng item trên frozen Exam snapshot; tổng điểm tính từ các item.
- Cho phép override auto-score của mọi loại câu; giữ original auto-score,
  manual-score, actor, timestamp và audit. Không nhập tổng độc lập.
- Feedback từng câu và toàn bài; có lưu nháp trước Finalize.
- Finalize yêu cầu mọi item bắt buộc đã xử lý, điểm hữu hạn và trong giới hạn.
- Essay AI unavailable giữ NEEDS_REVIEW, không biến lỗi AI thành điểm 0.
- Session SUBMITTED không đồng nghĩa Result GRADED. Trạng thái và gradedAt phải
  phản ánh thực tế chấm, không thời điểm tạo hàng DB.

## Regrade và công bố

- Sau Finalize, sửa điểm qua Regrade tường minh, bắt buộc lý do; audited,
  revisioned, không ghi đè lịch sử. Bản nháp sửa lại không xóa bản công bố cũ.
- Exam chọn tự công bố sau Finalize hoặc Release riêng; mặc định tự công bố.
- Objective auto-grade hoàn chỉnh tuân cùng chế độ công bố.
- Regrade tuân cùng chế độ; Student giữ bản đã công bố trước cho đến Release mới.
- Student thấy lịch sử các revision đã công bố: điểm cũ/mới nếu policy cho xem
  điểm, thời gian, lý do công khai; không lộ ghi chú nội bộ hay draft.
- Reporting nhận trạng thái/revision thực tế của Result, độc lập publication.

## Result visibility policy

- Theo Exam: showScore, showQuestionBreakdown, showFeedback bật mặc định.
- Bài làm của chính Student được xem trong kết quả đã công bố.
- showCorrectAnswers/explanation tắt mặc định; chỉ được mở khi Exam đã đóng.
- Backend enforce mọi field và bản công bố; frontend không tự suy đáp án.
- Chưa chấm xong hoặc chưa Release: trạng thái chờ phù hợp, không hiện 0 giả,
  không lộ điểm/feedback/key của draft qua API hoặc history.

## Event và notification

- Grading phát `grading.result.recorded.v1` đã được MAIN duyệt trong
  contracts/notification-reporting-decisions.md và contract của feature/reporting.
- Complete state, frozen questionVersion/position/points, stable eventId và
  monotonic positive resultRevision; outbox transaction + publisher confirms.
- Không answer key, answer content, JWT, secret trong Reporting event.
- Teacher OWNER/MANAGER nhận thông báo có bài cần review; Student nhận khi kết quả
  được công bố/cập nhật. Không gửi thông báo draft score cho Student.
- In-app/email/push theo cấu hình Notification hiện có; thông báo không chứa
  điểm, answer key hoặc private review notes. Deep link grading queue/result.
- Provider chỉ phát contract; nếu cần Notification/Reporting thay đổi, gửi
  Contract Request cho MAIN để giao đúng owner, không sửa foreign domain.

## Phạm vi triển khai và kiểm tra

- Backend: reuse feature/exam-taking-domain; frontend: reuse frontend/app.
- WORKER FAST MODE: đọc module liên quan, implement, module tests và migration
  liên quan, diff-check, commit, handoff, STOP. Không full-stack/Graphify/merge.
- MAIN review backend rồi khóa BACKEND CONTRACT LOCK trước khi giao frontend.
- Frontend chỉ hỏi một lượt UI/UX nếu còn quyết định quan trọng chưa rõ.
- MAIN merge backend trước frontend, reconcile contracts, refresh canonical
  Graphify và chạy unified runtime/E2E sau các worker; không lặp full validation
  ở từng worker. UI vẫn có gate READY FOR MANUAL UI VALIDATION theo rules.
- Giữ scheduling/calendar/timezone/auth/candidate-safety/snapshot regression.
- Không AI grading nâng cao, rubric generation, plagiarism, appeal, peer/bulk
  grading nâng cao, analytics dashboard mới hoặc feature tiếp theo. Không push.

## Các lock tiếp theo

- BACKEND CONTRACT LOCK: chờ provider handoff và MAIN focused review.
- UI/UX SCOPE LOCK: chờ frontend inspect/clarification theo contract đã duyệt.

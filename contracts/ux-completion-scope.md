# UX completion — Product/UI Scope Lock (2026-10-09)

Người dùng duyệt đề xuất hoàn thiện UX và bổ sung: click bài đăng Public phải
sang trang chi tiết; mobile màn nhiều thông tin ưu tiên trang mới có nút Quay lại.
Chế độ Codex thuần. MAIN điều phối run `run_cc71a91c447e`.

## Scope đợt này

- Thay sidebar cố định bằng launcher **Không gian** trên topbar, menu dạng ô
  theo role và hàng liên kết nhanh. Giữ toàn bộ route/quyền, Notification và logout.
- Public Bank: thanh tìm kiếm/lọc gọn phía trên; lọc phụ qua nút Bộ lọc;
  card sát nhau dùng đường viền chung 1px, giảm cover để ưu tiên nội dung.
- Public detail là route riêng trên desktop/mobile, back giữ filter/page;
  direct link và refresh hoạt động. Popup vẫn dùng cho xác nhận/quick action.
- Search/filter/sort/page nằm trong URL; không đưa token hoặc identity riêng tư vào URL.
- Teacher/Student lưu bài đăng theo tài khoản; Saved khác copy/fork/rating/view.
  Không tăng lượt xem/copy khi lưu; nội dung archived hoặc mất quyền không bị lộ.
- Personal Set: tìm kiếm và phân trang chọn câu ACTIVE; phân trang bộ; đổi thứ tự
  câu bằng thao tác accessible; thể hiện version/DRAFT/409, không autosave.
- My publications: phân biệt phiên bản nguồn, snapshot đã publish và thống kê thật.
- Đóng CR-MG-STUDENT-DISPLAY-NAME, CR-MG-SAFE-ANSWER-LABEL,
  CR-MG-FROZEN-EXPLANATION. Dùng trusted identity và frozen Exam/Result data;
  không lấy live Question để bù snapshot cũ. Policy và publication vẫn authoritative.

## Ownership và dependency

- Question Bank: bookmarks, query/pagination, optional explanation nguồn/version
  nếu model chưa có. Không chỉnh frontend/Exam/Auth.
- Exam Taking: frozen snapshot, safe result labels/explanation và student name.
  Giữ Browser Integrity chưa duyệt ở branch; MAIN chỉ tích hợp commit liên quan.
- APP: frontend duy nhất. Làm shell/layout/routes độc lập trước; chỉ nối field/API
  mới sau MAIN Backend Contract Lock. Không đoán contract hoặc fake dữ liệu local.
- MAIN: contracts/review/integration/Graphify/runtime/E2E.

## Invariants / gate

Giữ Tinos, màu và style đường viền EXAMINI; không đổi toàn bộ theme.
TEACHER != ADMIN; backend quyết định quyền. Rating 0–10 decimal, Copy/Rating
sort riêng, rolling windows, explicit view idempotency và snapshot/lineage giữ nguyên.

Worker FAST: module validation → commit → handoff → STOP. MAIN chạy integrated
validation sau review. UI dừng READY FOR MANUAL UI VALIDATION trước merge.
Giữ dirty work/stash; không push. Không tạo thêm frontend editor song song.

Auth recovery/email verification và Notification preferences là đợt tiếp theo;
không mở Browser Integrity, AI, webcam, source ingestion, community moderation
hoặc Organization feature mới trong scope này.

## API contracts

Các API/DTO bookmarks và Result mới chưa khóa tại thời điểm tạo scope này.
Provider gửi handoff; MAIN review trước frontend consumer. Tài liệu này không
biến API đề xuất trong worker prompt thành API đã triển khai.

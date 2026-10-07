# Exam Calendar — UI/UX Scope Lock

Người dùng trả lời **“duyệt”** ngày 2026-10-07 cho toàn bộ khuyến nghị Frontend
đã MAIN chuyển qua sáu nhóm quyết định. Nguồn câu hỏi Orca:
`msg_a1fb86f267f4`, Task `task_64a855e02cbf`, Dispatch `ctx_186d17bdb03e`.
Business authority vẫn là `exam-scheduling-scope.md`; API authority là
`exam-scheduling-api.md` và `notification-scheduling.md`.

## Điều chỉnh trực tiếp của user ngày 2026-10-07 — ưu tiên so với scope view cũ

Sau bản `30cb232`, user yêu cầu Frontend gom `/calendar` thành **một lưới tháng**
kẻ ô theo ảnh tham chiếu; bấm ngày mở popup liệt kê kỳ thi, bấm kỳ thi mở detail
panel/bottom sheet. Sau worker report, user nói **“gửi lên main để merge đi”**.
MAIN xác minh câu này qua terminal hiện hữu; handoff Orca `msg_c8fa4391b6bd`,
commit `35ea5cc`. Đây là yêu cầu/approval mới, không suy diễn từ approval cũ.

Điều chỉnh này thay các tab Agenda/Tháng/Tuần/Ngày trên `/calendar` cho cả role:
chỉ còn Tháng, mũi tên trước/sau, Hôm nay, weekend shading/status bars; mobile
hiện chấm và bấm ngày mở list popup. “Chưa có lịch” nằm dưới grid; Dashboard
Today/Upcoming vẫn giữ. Popup dùng keyboard/focus management hiện có.

Các mục cũ dưới đây là lịch sử scope đã implement/test ở `30cb232`; yêu cầu view
mới ưu tiên cho presentation. Không đổi scheduling/eligibility/timezone,
permission, Notification, snapshot hoặc rule backend. MAIN vẫn review/test
candidate mới trước merge; không push hoặc tự mở feature tiếp theo.

## 1. Vị trí và view

- Menu Lịch cho Teacher và Student tại `/calendar`, nhãn theo role.
- Agenda mặc định; đủ Agenda/Tháng/Tuần/Ngày, nhớ view cuối trên máy.
- Dashboard có Hôm nay/Sắp tới và link vào Lịch.
- Agenda nhóm Hôm nay/Sắp tới/Sau đó/Chưa có lịch/Đã qua. Không bịa ngày cho
  Exam chưa có lịch; calendar query phải xử lý hết pagination theo range.

## 2. Timezone

- Form và thẻ ghi giờ theo IANA timezone của Exam, không hardcode GMT+7.
- Lưới nhiều Exam xếp theo timezone máy người xem, ghi nhãn rõ.
- Khi khác timezone Exam, thêm dòng Giờ của bạn; không thay Instant vì display.
- UTC/offset/DST và availability/deadline theo backend contract. Client clock
  chỉ phục vụ display, không tự cấp quyền Join/Start hay kéo dài thời gian.

## 3. Thẻ, chi tiết, actions và mobile

- Thẻ gọn: tên, giờ, trạng thái chữ + icon và chỉ số chính. Teacher thấy
  lifecycle, participant/capacity, deadline rule và cấu hình tự mở/đóng khi API
  cung cấp; Student thấy availability, countdown, duration/rule, lượt còn lại,
  quan hệ assigned/joined/followed. Không invent số liệu/fields chưa có.
- Legend dưới toolbar: xanh cho available, viền đen cho upcoming/published,
  nét đứt cho draft/waiting, xám cho closed/submitted, đỏ cho missed/error;
  thông tin không chỉ dựa vào màu.
- Click mở panel phải trên desktop, bottom sheet trên mobile. Actions theo
  quyền tài nguyên: OWNER/MANAGER manage, PROCTOR read-only; Student lobby,
  resume, result, Follow/Unfollow. Có link xem đầy đủ.
- Teacher có tab Lịch trong Exam detail và Đặt lịch cho đề trên toolbar, chọn
  Exam rồi mở form. Không drag/drop, resize hoặc tạo Exam từ ô trống.
- Mobile ưu tiên Agenda; Tháng có ngày/chấm và danh sách ngày được chọn;
  Tuần/Ngày là danh sách thời gian dọc, không bỏ view.
- PUBLIC có Theo dõi tại discovery/panel/preview. Follow mới vào My Calendar
  và nhận Notification; không tự Join/cấp quyền Start. Early preview giữ nút
  Join/Start bị khóa với lý do backend.

## 4. Form lịch và mốc nhắc

- Start/end optional theo scope, IANA timezone, đủ ba deadline rules có giải
  thích, autoOpen/autoClose độc lập, reason khi bắt buộc, optimistic revision.
- Nhắc mặc định 24h/15m cho lịch mới; chip nhanh 1h/3h/2 ngày/7 ngày, mốc tùy
  chỉnh số + phút/giờ/ngày và Tắt nhắc. Tối đa 10 mốc riêng biệt, 1..43200 phút.
- Hiện thời điểm gửi theo timezone Exam; cảnh báo không gửi bù mốc đã qua.
- Save có xác nhận; 409 revision/admission có thông báo và tải lại/thử lại;
  DST gap/offset/overlap theo validation backend, không im lặng đổi giờ.

## 5. In-app Notification

- Chuông topbar + unread badge, refresh mỗi 60s và focus tab.
- Dropdown 10 mới nhất và trang `/notifications`, pagination, đọc một/đọc tất cả.
- Click đánh dấu đã đọc rồi mở Exam tương ứng; không tự bật toast.
- Shared API/auth runtime, không identity headers, không service tokens.

## 6. Browser push

- Chỉ xin permission khi user bấm Bật, không tự xin lúc login/mở Lịch.
- Thẻ trong dropdown/trang thông báo, giải thích, Bật/Tắt; trạng thái enabled,
  blocked, unsupported, server unconfigured. Gợi ý một lần có thể đóng ở Lịch.
- Service worker/subscription theo public API; logout/account switch hủy
  subscription của account cũ trên máy bằng DELETE best-effort.
- VAPID private key và Gmail credentials không đến frontend. Provider accepted
  không tự chứng minh browser display. Không fake delivery để làm test xanh.

## Acceptance và gate cuối

Frontend chỉ sở hữu `frontend/**`, backend read-only; thiếu contract gửi MAIN.
Giữ Phase 1 Product UI hiện có; responsive/keyboard/focus/semantic labels,
loading/error/empty và xử lý 401/403 theo backend. TEACHER != ADMIN.

Chạy tsc -b, build, validate-shared, relevant regression/UI tests, diff --check
và browser Teacher/Student với Gateway backend thật khi có thể. Report rõ mock,
fixture, NOT RUN và giới hạn môi trường. Commit rồi trả URL/checklist ở
**READY FOR MANUAL UI VALIDATION**; user duyệt UI trước MAIN final merge.
Không tự merge MAIN/push hoặc bắt đầu feature tiếp theo.

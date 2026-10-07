# Kết quả frontend: Lịch thi và đặt lịch

Nhánh `frontend/app`. Căn cứ: `contracts/exam-calendar-ui-scope.md` (user "duyệt", MAIN `f7c933d`), `exam-scheduling-api.md`, `notification-scheduling.md`.
Backend và contracts chỉ đọc, không sửa.
Commit mới nhất: `7a11e4a` (tắt push khi đổi tài khoản).
Trạng thái cuối: **READY FOR MANUAL UI VALIDATION**. MAIN và user chưa duyệt UI cuối.

## Đã có

| Phạm vi | Vị trí |
| --- | --- |
| `/calendar` (Teacher "Lịch thi", Student "Lịch của tôi"): Agenda (mặc định), Tháng, Tuần, Ngày; nhớ view đã chọn theo thiết bị | `src/features/calendar/CalendarPage.tsx`, `CalendarViews.tsx`, `ExamCard.tsx` |
| Thẻ gọn, chú giải (không chỉ dùng màu), panel bên phải / bottom sheet theo vai trò, khối "+N kỳ thi" khi giờ quá đông | `DetailPanel.tsx`, `calendar.css` |
| Form đặt lịch của Teacher: múi giờ IANA, DST gap/overlap, 3 deadline rule, tự mở/đóng, nhắc (mặc định 24h/15 phút, tùy chỉnh, tắt), lý do, bước xem lại, xung đột revision | `ScheduleDialog.tsx`, `ReminderEditor.tsx`, `scheduleModel.ts` |
| Tab "Lịch", đóng kèm lý do / mở lại ở trang đề; "Đặt lịch cho đề" trên lịch | `ExamDetailPage.tsx`, `LifecycleDialog.tsx`, `ScheduleExamPicker.tsx` |
| Student: Lobby theo availability của backend, xem trước khi chưa tới giờ, Follow/Unfollow đề PUBLIC, xem trước mã tham gia, Kỳ thi của tôi theo trạng thái backend, Hôm nay/Sắp tới trên dashboard | `ExamLobbyPage.tsx`, `FollowButton.tsx`, `MyExamsPage.tsx`, `JoinCodeForm.tsx`, `TodayUpcoming.tsx` |
| Thông báo trong app (chuông, `/notifications`) và push trình duyệt opt-in kèm service worker | `src/features/notifications/*`, `public/sw.js` |
| Tính toán múi giờ, không thêm thư viện ngày | `src/lib/zone.ts` |

Giữ nguyên các quy tắc: lưới lịch dùng múi giờ người xem và ghi rõ; thẻ luôn hiện múi giờ của Exam và dòng "Giờ của bạn" khi khác nhau;
`canJoin/canStart/canResume/reasonCode/serverNow/deadline` là kết quả của backend (đồng hồ trình duyệt chỉ để hiển thị và quyết định *khi nào hỏi lại*);
không tạo identity header, không có token nội bộ; TEACHER != ADMIN; không kéo thả, không resize, không tạo từ ô trống.

## Kết quả từng finding của MAIN

| Finding | Kết quả |
| --- | --- |
| Trục DST và đường "bây giờ" | Lưới đặt theo trục 24 hàng wall-clock. Browser thật với `America/Los_Angeles`, ngày 2030-03-10 (23h) và 2030-11-03 (25h): khối 10:00 nằm đúng hàng 10:00 (lệch 0 px, 41,6667%). Unit test cho ngày chuyển giờ mùa xuân, mùa thu, cắt khối và đường now. |
| Nhãn giờ khi DST overlap | Nhãn cửa sổ, hàng Agenda và khối lưới hiện offset từng đầu khi khác nhau ("01:30 GMT-7–01:30 GMT-8"). Exam "DST overlap" của MAIN hiển thị đúng trong browser. |
| Start-only OPEN không bị coi là "đã qua" | Exam mở không có giờ kết thúc và đang chạy vẫn nằm ở Hôm nay; khối 1 giờ chỉ để vẽ. Có unit test. |
| Join sớm sau khi xem trước | Nếu preview báo `canJoin=false` thì Join bị khóa kèm lý do của backend. Browser: Exam JOIN_CODE bắt đầu vào ngày mai. |
| Focus trên bottom sheet mobile | Một hook focus dùng chung cho Dialog và panel: giữ Tab trong modal, khóa cuộn, Escape chỉ đóng modal trên cùng, trả focus về nơi mở. Browser 390 px: 20 lần Tab/Shift+Tab không thoát ra ngoài, Escape đóng được, hết khóa cuộn, focus về thẻ; dialog "Sửa lịch" lồng nhau đóng trước và giữ khóa cuộn. MAIN cũng đã kiểm tra độc lập. |
| Tự làm mới đúng mốc giờ | Lịch và Lobby hỏi lại backend ngay sau mốc start/end/hết hạn kế tiếp, cộng thêm lần làm mới nhẹ mỗi 2 phút. Browser: đứng yên trên lịch, một Exam chuyển từ "Sắp diễn ra" sang "Cần tham gia" sau khoảng 13 giây kể từ `startAt`. |
| Dọn push khi bị buộc đăng xuất | Watcher trong auth runtime xử lý logout, hết hạn, refresh thất bại, đổi tài khoản và phiên đã chết lúc khởi động, dùng danh tính và token cũ; gỡ subscription trình duyệt trước, rồi DELETE phía server. Browser: refresh thất bại, chuyển về `/login`, record bị xóa, DELETE được gửi. |
| Enable đang chờ khi logout/đổi tài khoản (`msg_7fa7df8d7bd7`) | `enablePush` kiểm tra lại phiên sau mỗi lần await, chỉ gỡ đúng subscription mà nó giữ, không đăng ký cho tài khoản mới, và xóa record server do POST có thể đã tạo bằng token cũ. Có unit test cho từng điểm await, đã kiểm tra mutation. |
| Hai account enable đồng thời (`msg_5614578712e8`) | Lần đầu viết test với fake `subscribe()` luôn tạo object mới nên test không bắt được lỗi (cả hai mutation đều xanh). Sau khi sửa fake giống `PushManager` thật (một subscription cho mỗi registration), test chứng minh được rủi ro: không có hàng đợi thì undo của A gỡ endpoint của B. Đã sửa tối thiểu trong `pushClient.ts`: các lần enable xếp hàng, B chờ A xong và tự dọn; `undo` chỉ gỡ subscription do chính lần enable đó tạo (subscription có sẵn thì không đụng tới). Hai test mới; bỏ hàng đợi hoặc cho `undo` luôn unsubscribe đều làm đúng một test đỏ, và mã đã được khôi phục sau mỗi lần thử. Đây là kiểm chứng bằng fake trong unit test, không phải browser thật. |
| `reconcilePush` bất đồng bộ (`msg_af347a325eaf`) | Đã sửa trong `pushClient.ts` và `AuthRuntime.tsx`: reconcile nhận `isCurrent()` (cùng `sessionVersion` và `user.id`), kiểm tra lại sau mỗi await, chỉ gỡ đúng subscription đã đọc, và chạy chung hàng đợi với enable nên không xóa subscription đang chờ POST chưa có record. Ba test mới: reconcile A trễ khi B đã enable, reconcile cùng account lúc enable chờ POST, và reconcile vẫn gỡ subscription thừa của account khác. Bỏ guard sau lần đọc hoặc đưa reconcile ra ngoài hàng đợi đều làm đúng một test đỏ; mã đã khôi phục sau mỗi lần thử. Đây là kiểm chứng bằng fake trong unit test, không phải browser thật. |
| Tắt push khi đổi tài khoản (follow-up MAIN) | `disable()` cũ await DELETE của A rồi mới tra và gỡ subscription hiện tại, nên nếu DELETE trễ và B đã enable thì A gỡ nhầm endpoint của B. Đã sửa: logic chuyển sang `disablePush` trong `pushClient.ts`, đọc record và subscription của A trước await, DELETE bằng token của A (`revokePushSubscription`, không dùng token đang đăng nhập), kiểm tra lại phiên sau await (đổi phiên thì không tra hay gỡ endpoint hiện tại), và chạy chung hàng đợi với enable/reconcile nên enable của B chờ disable A xong. Hai test mới: disable A treo ở DELETE, logout, login B, B enable, disable A xong thì record và endpoint của B còn nguyên và mọi DELETE đều mang token A; và disable bình thường vẫn hủy đúng endpoint và record. Đối chứng: mô phỏng lại hành vi cũ (ngoài hàng đợi, tra endpoint hiện tại sau await) làm test 17 đỏ, mã xanh khi dùng bản sửa. Bỏ riêng guard hoặc riêng hàng đợi thì test vẫn xanh vì hai lớp bảo vệ chồng nhau (defense in depth). Đây là kiểm chứng bằng fake browser trong unit test, không phải push vật lý hay browser E2E. |
| Editor cũ không đổi lịch | Gửi nguyên `startTime/endTime` đã lưu (null vẫn null). Browser: sửa tiêu đề/mô tả của đề nháp không làm đổi `startAt` (tới từng ms), múi giờ, rule, cờ tự động, nhắc và `scheduleRevision`. |

## Ghi nhận về contract cho MAIN (không sửa backend)

1. `GET /exams/public` vẫn trả dạng cũ (không có `followed`, `timezone`, availability). UI gọi `/availability` cho từng đề công khai (tối đa 20 đề đầu). Nếu trả gộp thì giảm được N lần gọi.
2. `CalendarItem` không có `scheduleRevision`, cờ tự động hay mốc nhắc, nên editor đọc `GET /exams/{id}/schedule`. Không chặn việc gì.
3. Agenda hỏi từ 30 ngày trước đến 330 ngày sau (backend tối đa 366). MAIN đã xác nhận bằng đọc `ExamRepository.java:23–26` (`startAt < toAt AND (endAt IS NULL OR endAt > fromAt)`) rằng Exam không có `endAt` mà `startAt` trước `from` vẫn được trả nếu người xem có quyền. Đây là xác nhận bằng đọc query hiện hành, không phải E2E mới.
4. Link trong thông báo dựa vào `metadata.link` của Notification (chạy đúng trên stack thật).
5. Access token vẫn dùng được sau `/auth/logout`, chỉ refresh bị thu hồi (quan sát khi thử buộc đăng xuất).

## Kiểm chứng

Lệnh (trong `frontend/`): `npm exec -- tsc -b --pretty false` PASS; `npm run build` PASS (chunk chính khoảng 301 kB, route lazy); `bash tests/validate-shared.sh` PASS 83 test (auth runtime, UI logic, zone 7, calendar model 17, schedule model 7, notification model 5, push/auth 18); `npm run lint` PASS; `git diff --check` PASS.
`python3 tests/browser-smoke.py` PASS 8/8 ở lần chạy gần nhất (fixture API, chỉ kiểm route guard). Lần chạy đó trước hai thay đổi nhỏ cuối là `DetailPanel.tsx` và `pushClient.ts`; chưa chạy lại smoke sau chúng.

**Browser thật** (agent-browser, phiên riêng) với Gateway thật `127.0.0.1:18180`, Vite `127.0.0.1:13100`:
- Teacher: agenda, tháng, tuần, ngày, giờ đông; tạo lịch Europe/Paris (DST gap bị chặn, chọn overlap, đọc lại giá trị lưu từ API); xung đột revision rồi tải lại; publish/mở/đóng/mở lại kèm lý do và audit log; chuông và điều hướng thông báo; panel Proctor chỉ xem.
- Student: agenda/tháng với trạng thái thật, Follow đề PUBLIC, xem trước sớm với Join/Start bị khóa (backend cũng trả 409), tự làm mới đúng mốc, khóa Join theo preview mã, join, Lobby, start; màn 390 px không tràn ngang.

**Chỉ là unit test hoặc fixture, không phải browser thật:** toàn bộ race của push (enable chờ permission/subscribe/POST, hai account enable đồng thời, đổi tài khoản), các ngày DST ngoài Los Angeles, và smoke fixture 8/8.

**Chưa kiểm chứng:**
- **Hiển thị push vật lý KHÔNG được kiểm chứng.** Chrome tự động từ chối quyền thông báo (`denied`), nên không có subscription thật, không có provider gửi và không có thông báo OS. Không giả lập delivery. Trạng thái opt-in, service worker và cleanup chỉ được phủ bằng unit test với trình duyệt giả, cộng với đường logout bằng browser thật.
- Hộp thư Gmail không thuộc phạm vi UI này.
- Trục DST trên browser chỉ thử với đồng hồ Los Angeles; các múi giờ khác dựa vào unit test.
- Admin có mục menu "Lịch thi"; nội dung lịch của Admin theo đúng những gì backend trả cho tài khoản đó.

## Checklist kiểm tra thủ công

Teacher: mở Lịch thi (Agenda), chuyển Tháng/Tuần/Ngày, bấm thẻ (panel), "Đặt lịch cho đề", chọn đề, đặt múi giờ/bắt đầu/kết thúc/rule/nhắc, xem lại, lưu; thử giờ rơi vào DST gap (ví dụ 2027-03-28 02:30 Europe/Paris) và overlap (2026-10-25 02:30); sửa cùng một lịch từ hai tab để thấy thông báo revision; đóng một Exam trước giờ kết thúc (bắt buộc có lý do) rồi mở lại; kiểm tra chuông và `/notifications`.
Student: Lịch của tôi; Follow một đề PUBLIC ở Kỳ thi của tôi; mở Xem trước và xác nhận Join/Start còn khóa tới giờ; dán mã tham gia rồi bấm Xem trước; đứng yên xem Exam sắp diễn ra chuyển sang tham gia được mà không tải lại; thử ở chiều rộng điện thoại và chỉ dùng bàn phím (Tab, Escape trong bottom sheet).

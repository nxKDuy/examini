# FRONTEND MANUAL GRADING + RESULT REVIEW RESULT

Nhánh `frontend/app`, tiếp từ `35ea5cc`; bản sửa theo FIX REQUIRED của MAIN nằm ở commit riêng sau `63cd277` (xem `git log`). Căn cứ: `contracts/manual-grading-scope.md` và `manual-grading-api.md` (MAIN lock `0d6090e`, đọc bằng `git show`, không merge). Backend `a8ae421` + `e918493` chưa merge vào nhánh này.
Kết quả: **READY FOR MAIN REVIEW**. Gate runtime UI thủ công vẫn bắt buộc trước khi merge cuối.

## UI/UX SCOPE LOCK (user chốt toàn bộ A-G qua MAIN)

- A Student-first: chọn bài nộp rồi chấm từng câu, có nhảy nhanh giữa các câu cần chấm tay. Không Question-first, không Hybrid, không inbox toàn cục (Exam Detail → Grading theo từng kỳ thi).
- B Desktop 3 cột (hàng chờ, bài làm, điểm/nhận xét), tablet 2 cột, mobile danh sách → chi tiết có nút quay lại, khối nút hành động dính cuối màn. Overview là số liệu server của toàn kỳ thi; lọc trạng thái và đã/chưa công bố; phân trang.
- C Lưu nháp là nút phụ; Hoàn tất chấm là nút chính, có xác nhận và lưu nháp hiện tại trước; Công bố riêng (chỉ khi bài đã chấm mà bản hiện tại chưa công bố), có xác nhận; không autosave; cảnh báo khi rời bài có thay đổi chưa lưu.
- D Chấm lại bắt buộc lý do công khai, ghi chú nội bộ tùy chọn; panel so sánh "Học sinh đang thấy" với "Bản nháp/đã chấm" kèm chênh lệch; học sinh giữ bản cũ tới khi công bố bản mới.
- E Học sinh: điểm hoặc trạng thái chờ (không có 0 giả), nhận xét chung, từng câu, đáp án đúng chỉ khi backend trả, không nút "giải thích". Đáp án trắc nghiệm là UUID thì hiện "Đã chọn một phương án".
- F Lịch sử công bố gọn, bấm mở rộng: bản, thời gian, điểm, lý do công khai; không có audit riêng tư.
- G Danh tính học sinh tạm là mã rút gọn 8 ký tự cộng "Lần #n", có tooltip và nút sao chép mã đầy đủ; không bịa tên, không lookup.

## Routes, API và hành vi

| Route | Nội dung |
| --- | --- |
| `/grading?examId=&status=&released=&r=` | Chấm bài một kỳ thi. `r` là bài đang mở (nút Back của trình duyệt quay về danh sách) |
| `/exam/result/:sessionId` | Kết quả của học sinh |
| Exam Detail | Nút Chấm bài kèm badge số bài cần chấm tay từ `/submissions/overview` của chính kỳ thi |

API dùng (qua client chung, không identity header): `GET /submissions/overview`, `GET /submissions`, `GET /submissions/{id}`, `PUT .../draft`, `POST .../finalize|release|regrade`, `GET/PUT /exams/{id}/result-policy`, `GET /grades/session/{sessionId}`.
- Mọi thao tác ghi gửi `expectedRevision` hiện tại. Điểm không đổi gửi `null` để server giữ; `overrideReason` chỉ gửi khi sửa điểm tự động của câu khách quan (bắt buộc, riêng với nhận xét).
- Điểm phải hữu hạn trong `[0, pointValue]`; tổng chỉ là tạm tính trên màn hình, server tính khi lưu.
- 409 `STALE_RESULT_REVISION`: không tự gửi lại, không ghi đè, giữ nguyên bản nháp trên màn hình, nút gửi bị khóa, có cảnh báo và nút "Tải lại bản mới" (xác nhận trước khi bỏ thay đổi chưa lưu). Các 409 khác (sai trạng thái) hiện thông báo lỗi bình thường.
- Loading, rỗng, 401/403/404, lỗi mạng và 503 dùng `ErrorState`/thông báo chung của app (có Thử lại).
- Chính sách kết quả sửa được qua hộp thoại "Chính sách kết quả"; `showExplanation` bị khóa với ghi chú "chưa hỗ trợ", `showCorrectAnswers` ghi chú chỉ có hiệu lực khi kỳ thi đã đóng.
- Học sinh: phân biệt IN_PROGRESS, SUBMITTED, GRADING, NEEDS_REVIEW, GRADED_UNRELEASED, GRADED; chỉ vẽ trường backend thật trả; tự làm mới khi còn chờ.

## Notification

Không viết lại Notification. `notificationTarget` đã ưu tiên `metadata.link` (cùng origin): Teacher `/grading?examId=...` mở đúng hàng chờ của kỳ thi; Student `/exam/result/{sessionId}` mở kết quả. Thêm test cho hai link và cho link ngoài origin bị bỏ qua.

## Lệnh và kết quả

- `npm exec -- tsc -b --pretty false` PASS; `npm run build` PASS; `npm run lint` PASS (nằm trong validate-shared).
- `bash tests/validate-shared.sh` PASS 91 test (thêm 7 test `grading-model` và 1 test link Notification).
- `git diff --check` PASS.
- Kiểm tra đối chứng: sửa `toDraftRequest` luôn gửi điểm, hoặc cho `publicationOf` luôn trả CURRENT, đều làm đúng một test đỏ; mã đã khôi phục.
- Trình duyệt thật: chỉ kiểm tra trang `/grading` tải được (Teacher, Vite 13100 trỏ Gateway 18180, không tràn ngang). Backend chạy ở đó chưa có các endpoint mới (backend chưa merge), nên **chưa chạy luồng chấm, công bố, chấm lại hay kết quả học sinh trên runtime thật**. Logic quyết định (điểm, revision, trạng thái công bố, đáp án học sinh) được kiểm bằng unit test thuần; phần giao diện chưa có E2E.

## Contract Requests cho MAIN (không chặn frontend)

1. `studentDisplayName` trong `SummaryResponse`/`DetailResponse` của grading. Hiện chỉ có `studentId`; UI hiện mã rút gọn và attempt.
2. Frozen explanation trong snapshot và trường trả cho học sinh; hiện `showExplanation` bị khóa và không có giải thích nào được vẽ.
3. Nhãn/nội dung phương án trắc nghiệm candidate-safe cho `answer` và `correctAnswers` của học sinh. Hiện nếu `answer` là UUID, UI hiện "Đã chọn một phương án" và bỏ qua `correctAnswers` dạng UUID.
4. (Đã đóng, MAIN xác nhận) Các endpoint ghi trả `DetailResponse` có `resultRevision` mới.

## Sửa theo FIX REQUIRED của MAIN (năm mục)

1. **Giữ đúng hành động khi có nháp chưa lưu.** Mọi thay đổi URL (đổi kỳ thi, bộ lọc, bài nộp, sidebar, Link, nút Back/Forward của trình duyệt) do `useBlocker` giữ lại cùng đích đến đầy đủ; xác nhận mới `proceed()`, "Ở lại" gọi `reset()` và giữ nguyên nháp. Đổi trang hàng chờ (state, không đổi URL) dùng `createDeferredGuard` giữ nguyên cả hành động; xác nhận chạy đúng hành động đó một lần và đóng bài đang mở (nháp bị bỏ), hủy thì bỏ hành động. Việc về trang 0 khi đổi kỳ thi/lọc nay suy ra từ bộ lọc, không còn chạy trước khi người dùng xác nhận.
2. **SHORT_ANSWER.** Tách `isChoiceAnswer` (MCQ/TRUE_FALSE: bài làm là một phương án, có thể là UUID) khỏi `isAutoGraded` (thêm SHORT_ANSWER: sửa điểm tự động bắt buộc lý do nội bộ). Câu trả lời ngắn vẫn là văn bản tự do, không bị biến thành lựa chọn.
3. **Không gửi lại override cũ.** Sau khi override đã lưu (server trả `manualScore`), các lần lưu sau gửi `score: null`, `overrideReason: null` cho câu đó, kể cả khi chỉ sửa nhận xét hay điểm câu khác; gõ lại đúng giá trị cũ cũng không tính là đổi. Có test hồi quy cho MCQ và SHORT_ANSWER.
4. **Rời trang qua sidebar/Link/Back.** `App.tsx` đổi từ `BrowserRouter` sang `createBrowserRouter` với một route splat bọc nguyên cây `<Routes>` cũ (router dữ liệu, tối thiểu để dùng `useBlocker`); không đổi route nào. `beforeunload` giữ cho đóng tab/tải lại.
5. **Finalize sau khi lưu nháp.** `finalizeWithDraft`: lưu nháp, adopt revision vừa lưu (form, cache, baseline), rồi finalize đúng revision đó. Nếu finalize lỗi, revision đã lưu đã được adopt nên lần thử sau không dùng revision cũ; thông báo ghi rõ "Đã lưu bản nháp nhưng chưa hoàn tất chấm".

### Kiểm chứng của đợt sửa

- `tsc -b`, `build`, `lint` PASS; `validate-shared.sh` PASS 100 test (thêm `grading-flow` 7 test và các test SHORT_ANSWER/override mới trong `grading-model`); `git diff --check` PASS; smoke fixture cũ `browser-smoke.py` 8/8 PASS sau khi đổi router.
- Đối chứng (fault injection, đã khôi phục mã): bỏ SHORT_ANSWER khỏi auto-graded, coi SHORT_ANSWER là lựa chọn, bỏ `adopt`, dùng lại revision cũ, chạy hành động khi còn nháp, chặn cả khi đích đến trùng chỗ hiện tại, và luôn gửi điểm — mỗi lỗi làm đỏ đúng test tương ứng.
- **Browser thật trên API fixture** (`tests/grading-guard-smoke.py`, fixture `fixture-server.mjs`, không phải backend thật): sidebar khi đang có nháp hiện hộp xác nhận, "Ở lại" giữ URL và điểm đã gõ, xác nhận đến đúng `/dashboard`; Back của trình duyệt bị giữ ở nháp, "Ở lại" giữ nguyên, xác nhận hoàn tất Back; đổi kỳ thi: hủy giữ kỳ thi và nháp, xác nhận áp dụng đủ `examId=e2` và bỏ `r`. Chưa có E2E với backend chấm bài thật (backend chưa merge).

### Contract Requests (không chặn frontend, không fetch QB live)

1. `studentDisplayName` trong grading SummaryResponse/DetailResponse (hiện UI dùng mã rút gọn + lần làm).
2. Frozen explanation trong snapshot và trường trả cho học sinh (`showExplanation` đang khóa).
3. Nhãn/nội dung phương án candidate-safe cho `answer` và `correctAnswers` của học sinh (hiện UUID hiện "Đã chọn một phương án").
4. (Đã đóng) MAIN xác nhận `GradingController@e918493`: draft/finalize/release/regrade đều trả `DetailResponse` có revision mới, nên không còn là Contract Request. Ba CR còn lại ghi ở `contracts/manual-grading-contract-requests.md`.

## Follow-up

- Cần runtime sau khi MAIN tích hợp backend: chấm essay, override MCQ có lý do, Finalize với `autoRelease` bật/tắt, Release, Regrade giữ bản cũ cho học sinh, hai tab gây 409, học sinh thấy GRADED_UNRELEASED, lịch sử công bố, Notification hai chiều.
- Hộp thoại chính sách và `StaleRevision` chưa có test UI tự động.

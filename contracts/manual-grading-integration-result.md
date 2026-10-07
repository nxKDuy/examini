# Manual Grading + Student Result Review — kết quả tích hợp

## Phạm vi đã đóng

Queue theo Exam; navigation `Exam Detail → Grading`. OWNER/MANAGER được chấm,
không suy quyền toàn cục từ TEACHER. Student chỉ xem own result.
UI Student-first, desktop ba cột, tablet hai cột, mobile danh sách → chi tiết.
Draft, Finalize, Release và Regrade là thao tác riêng; không autosave liên tục.
Regrade có lý do công khai, giữ nguyên publication cũ cho Student khi draft đang sửa.
Student history chỉ chứa publication; không chứa private audit note.

## Commits tích hợp

| Phần | Commit nguồn | Tích hợp MAIN |
|---|---|---|
| Backend | `a8ae421`, `e918493` | cherry-pick `8c6af2f`, `2642235` |
| Frontend | `6ffa405` và ancestor `63cd277` | merge `6924626` |
| Notification | `0650a55` | merge `0310f0b` |
| Reporting/Analytics | `d03730c` và ancestors | merge `d8d057e` |
| Runtime/contract adapter | permission-only Analytics token, compose env | `7a74f87` |

Không kéo Browser Integrity chưa review từ branch backend vào MAIN.

## Validation trên code tích hợp `7a74f87`

Frontend: `npm exec -- tsc -b --pretty false`, `npm run build`,
`bash tests/validate-shared.sh`: PASS; 100 test frontend PASS.

Backend sử dụng Maven 3.9.11/JDK 21 trong Docker:

```sh
mvn -pl exam-service,exam-session-service,grading-service,notification-service,analytics-service \
  -am package -DskipTests=false '-DargLine=-Xmx384m -XX:MaxMetaspaceSize=256m' -q
```

`MAVEN_OPTS=-Xmx256m`. Lượt đầu JVM bị OS kết thúc, exit 137; lượt giới hạn
heap hoàn tất thành công. Exam 21 PASS/1 SKIP; Session 8, Grading 8,
Notification 68, Analytics 13 PASS. Exam OutboxContractTest bị abort/skip,
không tính là PASS; event runtime thật đã được kiểm tra riêng bên dưới.

Flyway runtime PostgreSQL: Exam V1–V3, Session V1–V2, Grading V1–V3,
Notification V1–V3, Analytics V1 thành công. Exam/Session/Grading/Notification/
Analytics/Gateway health UP.

## Một lượt E2E tích hợp

Gateway `http://127.0.0.1:18180`, frontend đã duyệt `http://127.0.0.1:13100`.
Đây là môi trường kiểm thử riêng với real PostgreSQL, Redis, RabbitMQ và các
backend; không dùng API fixtures. Các artifact thay đổi của MAIN được build và
cập nhật tại đây; Gateway giữ artifact bảo mật hiện hữu không thay code trong batch.
Không xác nhận các container môi trường port 3000/8080 đã được redeploy.

| Flow | Kết quả |
|---|---|
| Objective MCQ/TRUE_FALSE/SHORT_ANSWER | PASS: 15/15, release riêng, policy ẩn đáp án |
| Essay/manual | PASS: NEEDS_REVIEW không lộ điểm 0; draft/feedback/finalize/release, 3/5 |
| Mixed | PASS: auto 15 + manual 3 = 18/20 |
| Regrade | PASS: Student giữ 18 trong draft; release mới 19/20, hai publication |
| Unrelated Teacher | PASS: truy cập bị từ chối |
| Student isolation | PASS: không xem kết quả Student khác |
| HTTP 409 | PASS: stale grading revision bị chặn |
| Notification | PASS: Teacher cần review, Student release/regrade, đúng deep link |
| Analytics | PASS: initial/finalize/release/regrade cập nhật một logical result |
| Broker duplicate/stale | PASS: replay 12 event, revision 8/19 điểm và notification count không đổi |
| Scheduling | PASS: Start sớm bị chặn; auto-open, submit, manual grading, release thành công |
| Browser thật | PASS: Teacher grading; Student điểm/feedback/history hai publication |

Tổng 36 check. Script ban đầu đối chiếu sai notification entityId bằng examId;
đã sửa đọc examId trong metadata và tiếp tục trên case cũ, không sửa business code.
Evidence cục bộ: `.git/coordination/grading-final-integration/` (không chứa credential
trong report Git). Dataset manual validation người dùng đã duyệt được giữ nguyên;
E2E dùng Exam mới. Email sử dụng Mailpit; không coi đây là xác minh Gmail hoặc
browser push trên thiết bị thật.

## Graphify

MAIN refresh canonical graph bằng `scripts/shared_graph.py build --branch main`.
Graph phản ánh code đã merge của Grading/Notification/Analytics/frontend.
Đây là structural AST + document inventory, không phải kiểm chứng ngữ nghĩa bằng LLM.
Không thêm Participant & Attempt feature chưa triển khai vào graph.

## Follow-ups không chặn batch này

- AUTH/Exam: `studentDisplayName` theo Contract Request hiện hữu.
- Question/Exam: frozen explanation và safe answer label; không fetch live Question để bù.
- Analytics/MAIN: replay lịch sử kết quả đã delivered trước khi consumer được triển khai.
- MAIN: redeploy môi trường chính port 3000/8080 theo quy trình vận hành riêng;
  Gmail/browser push trên thiết bị chưa chứng nhận trong lượt này.
- Notification: notification review lịch sử không tự rút lại khi chấm xong.

Không push remote; không bắt đầu feature kế tiếp trong batch này.

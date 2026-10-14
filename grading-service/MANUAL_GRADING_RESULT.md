# MANUAL GRADING + STUDENT RESULT REVIEW — HANDOFF

## Trạng thái Git và phạm vi

- Nhánh: `feature/exam-taking-domain`.
- Baseline MAIN: `6e0424f` (`contracts/manual-grading-scope.md`).
- Merge đồng bộ an toàn: `7a82d31`; commit này chỉ là merge MAIN vào nhánh và không phải commit tích hợp task.
- Phạm vi thay đổi: `grading-service/**`, policy/result permission cần thiết trong `exam-service/**`, và `attemptNumber` DTO nội bộ trong `exam-session-service/**`.
- Không sửa frontend, Auth, Notification, Analytics/Reporting, Gateway, root config hoặc contract dùng chung.

## Domain và API

### Result policy của Exam

- Mặc định: `autoRelease`, `showScore`, `showQuestionBreakdown`, `showFeedback` bật; `showCorrectAnswers`, `showExplanation` tắt.
- `GET/PUT /exams/{examId}/result-policy`: chỉ OWNER/MANAGER của đúng Exam.
- `GET /exams/{examId}/internal/result-policy`: service token Exam hiện hữu; trả trạng thái Exam cùng policy.
- `GET /exams/{examId}/internal/permission?actor=...&manage=true`: token `ANALYTICS_EXAM_PERMISSION_TOKEN` chỉ được dùng đúng route GET này với `manage=true`; token rỗng hoặc dùng route khác bị từ chối.

### Manual grading

- `GET /submissions?examId=...&status=...` và `GET /submissions/{resultId}`: OWNER/MANAGER.
- `GET /submissions` nhận thêm filter `released=true|false`; `GET /submissions/overview?examId=...` trả count authoritative cho toàn Exam (`totalSubmissions`, `needsReview`, `pending`, `graded`, `released`, `pendingManualItems`) thay vì cộng page hiện tại.
- `PUT /submissions/{resultId}/draft`: lưu điểm/feedback từng item; tổng điểm luôn được tính lại từ item, không nhận total từ client. Override objective auto-score phải có `overrideReason` riêng.
- `POST /submissions/{resultId}/finalize`: yêu cầu mọi item đã có điểm hợp lệ trong `[0, frozenPoints]`.
- `POST /submissions/{resultId}/release`: công bố riêng khi `autoRelease=false`.
- `POST /submissions/{resultId}/regrade`: yêu cầu public reason, giữ revision đã công bố gần nhất cho Student trong lúc draft mới đang xử lý.
- Mọi mutation dùng `expectedRevision`; revision cũ trả HTTP `409` với detail `STALE_RESULT_REVISION`.
- Objective giữ `autoScore`; override ghi `manualScore`, actor/thời điểm và audit old/new. Essay AI chỉ tạo suggestion hữu hạn trong bounds; dù AI thành công vẫn là `NEEDS_REVIEW` cho đến khi Teacher quyết định. AI lỗi không tạo điểm 0 giả.
- Queue/detail trả `attemptNumber`, `pendingManualItemCount`, draft `score/resultRevision` và `publishedScore/publishedRevision` tách biệt. Các metadata này được lưu trên result nên list không gọi Feign theo từng dòng.
- OWNER/MANAGER được chấm; PROCTOR, teacher không liên quan và role ADMIN không có membership bị từ chối bởi quyền Exam authoritative.

### Student result

- `GET /grades/session/{sessionId}` chỉ cho Student sở hữu session/result.
- Chưa có result trả trạng thái Session thật (`IN_PROGRESS`, `SUBMITTED`); result chưa công bố trả `NEEDS_REVIEW`, `GRADING` hoặc `GRADED_UNRELEASED` đúng phase, không rò điểm hoặc draft.
- DTO Student gồm `examId`, `attemptNumber`, `submittedAt`, và `gradedAt/releasedAt` của chính revision công bố.
- Response chỉ đưa score, breakdown và feedback theo policy; trường bị tắt được omit ở JSON.
- Đáp án đúng chỉ được đưa khi `showCorrectAnswers=true` và Exam đã `CLOSED`.
- Lịch sử công bố giữ revision, thời điểm, điểm theo policy và public regrade reason; private audit note không xuất hiện.
- `showExplanation` được lưu và mặc định tắt. Frozen snapshot hiện chưa có trường explanation, nên backend chưa thể trả nội dung explanation kể cả khi bật; cần contract snapshot additive trước khi dùng khả năng này.

## Persistence và concurrency

- `grading-service/V3__manual_grading_results.sql` bổ sung revision/publication/audit/outbox, attempt/pending/published metadata, item auto/manual score, frozen question version/position và chuyển thời gian kết quả sang `timestamptz`.
- `result_publications` giữ snapshot công bố bất biến gồm `gradedAt/releasedAt`; `grading_audit` tách public reason khỏi private note và ghi questionId, old/new score, old/new feedback, override reason, actor, time, revision.
- Row lock pessimistic cộng `expectedRevision` đảm bảo hai draft đồng thời chỉ một request thành công, request còn lại nhận `409`.
- Unique constraints bảo vệ một result/session và một logical outbox event theo result revision/routing key.
- `exam-service/R__manual_grading_policy.sql` là repeatable migration additive để tránh va chạm version với các commit Phase 2 chưa tích hợp MAIN.
- Migration đã được kiểm tra trên PostgreSQL 16 cả schema mới và nâng cấp dữ liệu legacy; bản ghi GRADED cũ được backfill publication revision 1.

## Event contracts

Tất cả event được ghi cùng transaction vào outbox, dùng event ID ổn định, publisher confirms và retry:

- `examini.grading.events` / `grading.result.recorded.v1`: envelope analytics đã duyệt, gồm `resultId`, positive `resultRevision`, session/exam/candidate/attempt, status, score tổng, thời điểm, và item frozen `questionId/questionVersion/questionType/position/pointValue/answered/needsReview/awardedPoints`. Không có answer/key/feedback riêng tư.
- `examini.events` / `grading.review.required`: `{resultId, sessionId, examId, examTitle, resultRevision}`.
- `examini.events` / `grading.result.released`: `{resultId, resultRevision, sessionId, examId, examTitle, studentUserId, gradingMode}`.
- `examini.events` / `grading.result.regraded`: `{sessionId, examId, examTitle, studentUserId, resultRevision}`.
- Notification chỉ phát Student event khi một revision được công bố; không phát cho draft. Notification consumer commit `0650a55` đã được MAIN review nhưng chưa thuộc patch này.

Relay retry (V6, 2026-10-14):
- Publish is `mandatory` with confirms. An attempt fails on a broker error, a nack, a 5s confirm timeout, or `UNROUTABLE` (the broker returned the message because no queue is bound to that exchange/routing key).
- Each failure stores `last_error` and `next_attempt_at`. Backoff is `retry-base-ms·2^(n-1)`, capped at `retry-max-ms` (defaults `grading.events.retry-base-ms=2000`, `grading.events.retry-max-ms=900000`). Every failure is logged as WARN with eventId, exchange, routingKey, attempt, nextAttemptAt and the reason.
- After `grading.events.max-attempts` (default 200, about 2 days at the cap) the row is `parked=true` and logged as ERROR.
- To replay a parked row: `UPDATE grading_event_outbox SET parked=false, next_attempt_at=NULL WHERE event_id=…`.
- Event contracts and payloads are unchanged.

## Frontend contract cần tích hợp sau review backend

- UI chấm bài phải gửi `expectedRevision` ở mọi draft/finalize/release/regrade và xử lý `409 STALE_RESULT_REVISION` bằng tải lại dữ liệu.
- Điểm nhập theo từng `questionId`; server trả `autoScore`, `manualScore`, effective `score`, `needsReview`, feedback và frozen points. Khi override objective, request phải gửi `overrideReason` không rỗng.
- Queue/detail dùng `attemptNumber`, `pendingManualItemCount`, `score/resultRevision` cho draft hiện tại và `publishedScore/publishedRevision` cho bản Student đang thấy; endpoint overview cung cấp tổng toàn Exam và filter `released`, UI không cần gọi thêm Session theo từng dòng.
- Student xử lý rõ các trạng thái `IN_PROGRESS`, `SUBMITTED`, `GRADING`, `NEEDS_REVIEW`, `GRADED_UNRELEASED`, `GRADED`.
- UI Student phải render theo trường thực có trong response; không suy ra đáp án đúng, draft hay policy từ client.
- Regrade cần hai vùng riêng: `publicReason` bắt buộc và `privateAuditNote` tùy chọn, private note không được hiển thị cho Student.

## Kiểm thử

Môi trường: Maven `3.9.11`, Eclipse Temurin JDK `21.0.9`, PostgreSQL `16.15` cô lập trong container `manual-grading-2fdb-pg`.

- `docker run --rm --network container:manual-grading-2fdb-pg -v /Users/mac/orca/workspaces/DATN/exam-taking-domain:/workspace -v /Users/mac/.m2:/root/.m2 -w /workspace/grading-service maven:3.9.11-eclipse-temurin-21 mvn test -DskipTests=false`: PASS, 8/8; objective, AI success/unavailable đều bắt buộc Teacher review, trạng thái Student, draft/finalize, score bounds, item audit, publication/history, authorization/isolation, stale revision/concurrency, outbox retry/event payload.
- `exam-session-service: mvn test -DskipTests=false`: PASS, 18/18; gồm attempt DTO và toàn bộ regression Session/Integrity hiện có.
- `exam-service: mvn test -DskipTests=false`: PASS, 27 chạy, 26 PASS và 1 SKIP có chủ đích do RabbitMQ optional; gồm policy/permission cùng regression Exam/Scheduling/Integrity.
- Flyway PostgreSQL mới: V1 → V3 PASS trong test Grading trên PostgreSQL 16.
- Flyway PostgreSQL legacy: V1/V2 + dữ liệu GRADED → V3 PASS; publication/revision/timestamptz được xác nhận.
- `git diff --check`: PASS.

## Phụ thuộc và follow-up

- MAIN cần cấu hình secret riêng `ANALYTICS_EXAM_PERMISSION_TOKEN`; patch không sửa root env/shared config.
- MAIN sẽ tích hợp Notification consumer `0650a55` và Reporting consumer đã review; patch này chỉ cung cấp producer/API permission.
- Kết quả lịch sử trước V3 không có frozen `questionVersion` trong schema cũ; migration không phát replay analytics tự động. Nếu Reporting cần backfill lịch sử, cần workflow replay có kiểm soát lấy snapshot authoritative.
- Cần bổ sung explanation vào frozen snapshot contract trước khi bật hiển thị explanation.

## Kết luận

`READY FOR MAIN REVIEW`

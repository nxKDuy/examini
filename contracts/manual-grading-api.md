# BACKEND CONTRACT LOCK — MANUAL GRADING

MAIN focused review: PASS sau các task commit `a8ae421` và
`e91849325a6036013003149cb86edfffd470fbc2`. Backend đã tích hợp vào MAIN qua
`8c6af2f` và `2642235` bằng cherry-pick riêng hai task commit, không kéo theo
Browser Integrity ngoài scope. Frontend `63cd277` + `6ffa405` đã review focused
PASS; người dùng đã duyệt UI thủ công. Backend, frontend, Notification và
Analytics đã tích hợp vào MAIN. Validation tích hợp thực tế và giới hạn môi trường
được ghi tại [manual-grading-integration-result.md](manual-grading-integration-result.md).
Business scope:
[manual-grading-scope.md](manual-grading-scope.md).

## Quyền và timestamps

API frontend qua Gateway, access JWT và shared API/auth runtime. Không tự tạo
identity headers. OWNER/MANAGER đúng Exam được quản lý; PROCTOR và Teacher không
liên quan bị từ chối. Student chỉ xem own result. TEACHER != ADMIN.
Timestamps kết quả là UTC Instant ISO-8601; frontend hiển thị theo policy timezone
hiện hữu. Question content/version/points là frozen snapshot, không fetch live QB.

## API frontend

| Method | Route | Capability |
| --- | --- | --- |
| GET/PUT | `/exams/{examId}/result-policy` | Đọc/sửa toàn bộ result policy |
| GET | `/submissions?examId=&status=&released=&page=&size=` | Queue theo Exam, Spring Page |
| GET | `/submissions/overview?examId=` | Counts toàn Exam |
| GET | `/submissions/{resultId}` | Chi tiết chấm bài |
| PUT | `/submissions/{resultId}/draft` | Lưu nháp điểm và feedback |
| POST | `/submissions/{resultId}/finalize` | Hoàn tất grading |
| POST | `/submissions/{resultId}/release` | Công bố revision đã finalize |
| POST | `/submissions/{resultId}/regrade` | Bắt đầu sửa kết quả tường minh |
| GET | `/grades/session/{sessionId}` | Candidate-safe own result |

Queue bắt buộc `examId`; chưa có global grading inbox hoặc query cross-Exam theo
Question. `status`: PENDING, NEEDS_REVIEW, GRADED. `released=true/false` lọc có/chưa
có publication; bản đang regrade vẫn có publication cũ. Không suy Released từ
GRADED đơn thuần. Page: content/totalElements/totalPages/number/size.

## Request DTO

- Policy PUT gửi đủ boolean `autoRelease`, `showScore`, `showQuestionBreakdown`,
  `showFeedback`, `showCorrectAnswers`, `showExplanation`. Bốn field đầu mặc định
  true; correct answers/explanation mặc định false.
- Draft: `{expectedRevision, feedback, items:[{questionId, score, feedback,
  overrideReason}]}`. Score nullable để chỉ cập nhật feedback; feedback null xóa
  feedback. Override objective auto-score bắt buộc overrideReason không rỗng,
  riêng với feedback public. Điểm hữu hạn trong `[0, pointValue]`; tổng server tính.
- Finalize/Release: `{expectedRevision}`.
- Regrade: `{expectedRevision, publicReason, privateAuditNote}`; publicReason bắt
  buộc, privateAuditNote tùy chọn và tuyệt đối không hiện cho Student.

## Teacher response

Summary: `id`, `examId`, `examTitle`, `studentId`, `attemptNumber`, `status`,
`score` (draft/current), `totalScore` (max), `publishedScore`,
`pendingManualItemCount`, `submittedAt`, `gradedAt`, `durationSeconds`, `feedback`,
`resultRevision`, `publishedRevision`. Detail thêm `items`.

Item: `questionId`, `questionVersion`, `position`, `questionContent`,
`questionType`, `pointValue`, `answerContent`, `selectedOptionContent`, `autoScore`,
`manualScore`, `awardedPoints`, `needsReview`, `feedback`, `manualGradedBy`,
`manualGradedAt`. Essay autoScore chỉ là suggestion; vẫn cần Teacher chấm.

Overview: `totalSubmissions`, `needsReview`, `pending`, `graded`, `released`,
`pendingManualItems`. `released` là publication count, có thể overlap các nhóm
current status khi đang regrade; không cộng mọi counter để suy total.

## Lifecycle và concurrency

- Result persisted: NEEDS_REVIEW, PENDING (draft đã xử lý), GRADED (finalized).
- Finalize chặn unresolved item/null score/bounds sai; gradedAt do server quyết định.
  Nếu autoRelease=true thì đồng thời freeze publication và gửi Student event.
- Release chỉ nhận GRADED; publication độc lập grading state. Revision đã được
  công bố không phát lại event khi gọi Release với revision hiện hành.
- Regrade giữ publication cũ, tạo current PENDING; sửa draft/finalize không lộ
  draft cho Student. Chỉ publication mới thay thế bản Student nhìn thấy.
- Mutation dùng current `resultRevision`, tăng revision server-side; stale trả
  HTTP 409 `STALE_RESULT_REVISION`. Không tự retry overwrite hoặc mất local draft.
- Audit lưu actor/time/revision/item/old-new score/feedback/reason; private audit
  không đi vào Student response hoặc Reporting/Notification event.

## Student response và visibility

`sessionId`, `examId`, `title`, `attemptNumber`, `status`, `resultRevision`,
`submittedAt`, `gradedAt`, `releasedAt`, `score`, `totalScore`, `feedback`,
`questions`, `history`. Trường nullable dùng NON_NULL, policy tắt thì omit.

Status: IN_PROGRESS/SUBMITTED từ Session khi chưa có result; GRADING,
NEEDS_REVIEW, GRADED_UNRELEASED khi chưa công bố; GRADED khi có publication.
GRADED_UNRELEASED không có score/feedback. Không thay missing score bằng 0.
Trong regrade, Student vẫn nhận status/score/gradedAt của publication cũ.

Student item: `questionId`, `questionVersion`, `position`, `questionContent`,
`questionType`, `pointValue`, `answer`, `awardedPoints`, `feedback`, `correctAnswers`.
Own answer vẫn được xem sau publication; showQuestionBreakdown điều khiển
awardedPoints, showFeedback điều khiển feedback, showScore điều khiển total/max.
Correct answers chỉ khi showCorrectAnswers=true và Exam CLOSED, lấy frozen key.
History: resultRevision/releasedAt/score/totalScore/publicReason theo policy.

Các thiếu sót frozen explanation, safe answer label và studentDisplayName là
[Contract Requests không chặn](manual-grading-contract-requests.md); frontend
tiếp tục dùng fallback đã được người dùng duyệt, không lấy live QB để bù snapshot.

**Giới hạn thật:** frozen snapshot chưa có explanation nên backend chưa trả
explanation dù policy có field. Không hiển thị toggle như capability hoạt động,
không tự fetch live QB hoặc suy key. Own MCQ answer có thể là option UUID; dùng
snapshot đã có nếu cần resolve label, báo Contract Request khi thiếu dữ liệu.

## Errors

400 validation/required item/invalid filter; 401 invalid access token;
403 permission/isolation; 404 result không tồn tại; 409 stale revision hoặc sai
lifecycle; 503 missing/unavailable required service. Error response ProblemDetail
với detail; không phụ thuộc một bộ code mới chưa implement.

## Events và dependencies

- `examini.grading.events` / `grading.result.recorded.v1`: complete state cho
  initial/draft/finalize/release/regrade, stable eventId, positive resultRevision,
  frozen question outcomes; không answer/key/feedback riêng tư.
- `examini.events`, envelope eventId/eventType/eventVersion=1/occurredAt/payload:
  `grading.review.required`, `grading.result.released`, `grading.result.regraded`.
  Student event chỉ khi publication; deep links Teacher `/grading?examId=...`,
  Student `/exam/result/{sessionId}`. Consumer reviewed: Notification `0650a55`.
- Reporting consumer reviewed `feature/reporting@d03730c`; MAIN tích hợp và kiểm
  tra duplicate/redelivery/stale revision → một logical result/session.
- Analytics permission provider GET `/exams/{id}/internal/permission?actor=&manage=true`
  chỉ nhận configured `ANALYTICS_EXAM_PERMISSION_TOKEN` trên route này, không keys.
- Grading migration V3 và Exam repeatable policy. Worker tests: Grading 8 PASS,
  Session 18 PASS, Exam 26 PASS/1 optional SKIP; PostgreSQL fresh/legacy PASS.
  MAIN runtime/Notification/Analytics/browser unified E2E chưa chạy cho feature này.

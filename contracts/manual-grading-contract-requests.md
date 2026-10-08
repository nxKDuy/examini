# Contract Requests — Manual Grading / Student Result

Requester: `frontend/app`. Provider: Exam Taking / Grading; phối hợp Question Bank
khi cần dữ liệu nguồn cho frozen snapshot. MAIN điều phối, không sửa domain thay owner.

Trạng thái: **PROPOSED / FOLLOW-UP**, không phải API đã triển khai.
Breaking change: không, dự kiến additive. Blocking: **không** cho frontend hiện tại.
Điều hướng giữ **Exam Detail → Grading**, hàng chờ theo từng Exam.

## CR-MG-FROZEN-EXPLANATION

Need: explanation gắn với đúng `questionId` / `questionVersion` được snapshot khi
Publish Exam, không thay đổi khi Question Bank sửa bản nguồn.

Proposed response: trường explanation optional trong Student result, chỉ trả sau
publication và khi result visibility policy cho phép. Provider chốt tên DTO/API
trước khi consumer sử dụng. Không lấy explanation của live Question để bù snapshot.

Hiện tại: frontend không hiển thị explanation; không coi `showExplanation` là
capability hoạt động khi frozen snapshot chưa hỗ trợ.

## CR-MG-SAFE-ANSWER-LABEL

Need: nhãn/nội dung phương án Student đã chọn từ frozen options; hiện own answer
có thể chỉ là UUID. Không trả grading key hoặc correct-option flag ngoài policy.

Proposed response: optional `selectedOptionContent` / `answerDisplay` hoặc DTO
tương đương được provider duyệt. Correct-answer label chỉ trả khi backend cho phép
theo publication, visibility policy và lifecycle Exam. Không resolve bằng live QB.

Hiện tại: UUID chưa resolve hiển thị “Đã chọn một phương án”; không suy đáp án đúng.

## CR-MG-STUDENT-DISPLAY-NAME

Need: `studentDisplayName` lấy qua trusted identity contract, không từ caller
identity headers. Provider quyết định lookup/snapshot phù hợp và quyền truy cập.

Hiện tại: Student ID rút gọn + attempt, tooltip và copy full ID; không bịa tên.

## Delivery / acceptance

MAIN đã gửi frozen explanation và safe answer label tới workspace Exam Taking qua
Orca: `msg_9d611a8c5c5c`. Đây là yêu cầu ghi nhận follow-up, chưa giao implementation.
Provider phải gửi contract handoff trước khi frontend sử dụng các field mới.
Ba thiếu sót này không chặn implementation, targeted validation hoặc MAIN review
của feature theo scope UI/UX A–G đã được người dùng chốt.

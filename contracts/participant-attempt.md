# Participant & Attempt Management — API/event contract

CURRENT: MAIN đã focused review và tích hợp `41aa1c3` (cherry-pick của `1945d6a`,
Worker A). Scope: [parallel-participants-organization-scope.md](parallel-participants-organization-scope.md) mục A.
Chi tiết đầy đủ ở handoff worker; đây là phần khóa cho consumer.

## Mô hình
- `ExamParticipant` (PK exam_id+user_id): source ASSIGNED|JOIN_CODE|PUBLIC, status ACTIVE|REVOKED,
  revoke giữ lịch sử, lý do revoke tùy chọn và công khai. Follow không tạo participant.
- Counts: `registered` = mọi hàng, `eligible` = ACTIVE (chiếm maxParticipants), `revoked`.
- Giới hạn hiệu lực = `Exam.attemptLimit` (mặc định 1) + `extraAttempts` của Student.
  Start thành công mới tiêu hao; lượt bị invalidate vẫn tính, không tự hoàn.
- Invalidate: IN_PROGRESS → CANCELLED; SUBMITTED/AUTO_SUBMITTED giữ status + cờ invalid;
  Grading giữ result/audit, chặn draft/finalize/release/regrade (409 `RESULT_INVALIDATED`),
  loại khỏi aggregate hợp lệ.

## Public API (qua Gateway; backend authoritative)
| API | Quyền |
|---|---|
| `GET /exams/{id}/participants?status=&source=&userId=&page=&size≤100` → `{content,page,size,totalElements,totalPages,counts}` | OWNER/MANAGER/PROCTOR |
| `GET /exams/{id}/participants/counts` → `{registered,eligible,revoked}` | OWNER/MANAGER/PROCTOR |
| `POST /exams/{id}/participants` `{userIds}` XOR `{emails}` 1..100 → `{results[{input,userId,displayName,outcome}],counts}` | OWNER/MANAGER |
| `POST /exams/{id}/participants/{user}/revoke` `{reason?}`; `DELETE` = revoke mềm | OWNER/MANAGER |
| `POST /exams/{id}/participants/{user}/attempt-grants` `{quantity=1..100, reason, requestId}`; `GET` lịch sử | OWNER/MANAGER |
| `GET /exams/{id}/participation/me` | Student participant |
| `POST /sessions/{sessionId}/invalidate` `{reason}` | OWNER/MANAGER |
| `GET /sessions/exam/{id}/mine`; `GET /sessions/exam/{id}/attempts?userId=` | Student own; OWNER/MANAGER/PROCTOR |

**Breaking:** `GET /exams/{id}/participants` không còn trả Map userId→source; frontend
`ExamDetailPage` phải chuyển sang shape mới (task Participant UI).
Outcome assign: ASSIGNED, REINSTATED, ALREADY_PARTICIPANT, CAPACITY_FULL, NOT_FOUND, NOT_A_STUDENT.
Assign fail closed 503 khi identity provider lỗi/chưa cấu hình (`EXAM_IDENTITY_TOKEN`, `AUTH_SERVICE_URL`).
Route `internal` mới (`attempt-limit`, `attempt-summary`, `attempts`) bị Gateway chặn.

## Events (APPROVED v1, envelope chuẩn `examini.events`, routing key = eventType)
1. `exam.participant.changed` — `{examId,examTitle,userId,cause,actorUserId,quantity?,publicReason?,recipientRevision,registeredCount,eligibleCount,revokedCount}`,
   cause ∈ ASSIGNED|REINSTATED|JOINED|REVOKED|ATTEMPT_GRANTED. Consumer dedup theo eventId;
   counts snapshot lấy theo `recipientRevision` lớn nhất (ATTEMPT_GRANTED không tăng revision).
2. `exam.attempt.invalidated` — `{sessionId,examId,userId,attemptNumber,status,reason,actor,invalidatedAt}`.
   Grading có queue riêng; Notification (Student) và Analytics (loại attempt khỏi aggregate) bind topic.
3. `exam.recipients.changed` không đổi enum; `grading.result.recorded.v1` không đổi payload.
Không chứa đáp án, điểm, email hoặc private note.

# BACKEND CONTRACT LOCK — Organization Management

MAIN review PASS: provider `471b290`, tích hợp cùng Auth identity resolver
`0daa663`; shared runtime wiring `62918a8`. 15 tests trên PostgreSQL thật PASS;
runtime cross-service sẽ MAIN xác minh một lần cuối batch. Không có UI approval.

Scope: [parallel-participants-organization-scope.md](parallel-participants-organization-scope.md).
Org roles là resource-scoped; verified JWT canonical persona quyết định Teacher
create, legacy role chỉ fallback khi persona absent. Không quyền từ identity headers.
Org Admin không là system Admin. Teacher non-member không quyền quản lý.

## Public API (qua Gateway `/organizations/**`, `Authorization: Bearer <access JWT>`; ProblemDetail lỗi)
Phân trang: `page` (0-based), `size` (mặc định 20, tối đa 50) → `{items,page,size,totalElements,totalPages}`.
| Method & route | Quyền | Ghi chú |
| --- | --- | --- |
| POST /organizations `{name≤120,description?≤1000}` | TEACHER | 201 OrganizationView (me=OWNER) |
| GET /organizations?status&q&page&size | user | Org của tôi (kèm `me`: membershipId/role/canReview/capabilities) |
| GET /organizations/{id} | member | + activeMemberCount |
| PATCH /organizations/{id} `{name?,description?,revision}` | OWNER | 409 stale revision / archived |
| POST /{id}/archive `{revision}` | OWNER | |
| GET /{id}/capabilities | member | Access |
| GET /{id}/members?status=ACTIVE\|REVOKED\|LEFT\|ALL&role&page&size | member (history chỉ OWNER/ADMIN) | displayName best-effort từ Auth; lỗi → bỏ qua |
| PATCH /{id}/members/{membershipId} `{role?:ADMIN\|MEMBER, canReview?, revision}` | theo trên | revision bắt buộc |
| DELETE /{id}/members/{membershipId}?revision= | OWNER/ADMIN | 204; REVOKED |
| POST /{id}/leave | member | 204; LEFT; OWNER cuối 409 |
| POST /{id}/transfer-ownership `{targetMembershipId,revision(org)}` | OWNER | |
| POST /{id}/invitations `{userId｜email, role:ADMIN｜MEMBER}` | OWNER/ADMIN | 201 mới / 200 trùng |
| GET /{id}/invitations?status | OWNER/ADMIN | |
| POST /{id}/invitations/{invId}/revoke | OWNER/ADMIN | |
| GET /organizations/invitations/mine?status | user | cho người nhận |
| POST /organizations/invitations/{invId}/accept → MemberView ; /decline → InvitationView | recipient | |
| GET /{id}/audit | OWNER/ADMIN | paginated |
Lỗi: 400 validate, 401 JWT, 403 quyền, 404 ẩn/không có, 409 revision/lifecycle/last-owner/archived, 503 identity provider.
DTO chính: OrganizationView{id,name,description,status,revision,createdAt,updatedAt,archivedAt,me,activeMemberCount}; MemberView{membershipId,userId,displayName,role,status,canReview,capabilities,joinedAt,endedAt,revision}; InvitationView{id,organizationId,organizationName,recipientUserId,recipientDisplayName,role,status(effective),invitedBy,createdAt,expiresAt,respondedAt,revision}.


## Semantics đã review

- Membership revoke/leave/role change hiệu lực ở lần check kế tiếp, không cache allow.
- ARCHIVED giữ read-only history; BANK_USE/SUBMIT/REVIEW/MANAGE không còn được cấp.
- Creator OWNER; transfer ownership hạ OWNER cũ thành ADMIN, không có thời điểm zero owner.
- OWNER quản lý ADMIN/MEMBER; ADMIN chỉ quản lý MEMBER/canReview và mời MEMBER.
- Invitation existing user ID hoặc exact persisted email; không normalize email tại
  consumer, không autojoin. Accept/decline chỉ recipient signed access JWT.
- TTL 7 ngày; list/filter/count dùng effective EXPIRED ngay cả trước sweep.
- Conflict revision/lifecycle → 409; frontend không silently overwrite.
- Member displayName là best-effort; missing phải có fallback ID, không suy identity.
- Organization authority không tự cho Personal/Public authoring hoặc Exam management.

Internal QB boundary: [organization-authorization.md](organization-authorization.md).
Events: [organization-events.md](organization-events.md). Public API hiện tại không
support unarchive, invite chưa đăng ký, second auth system hoặc CSV import.

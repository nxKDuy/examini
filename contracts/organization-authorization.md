# Organization → Question Bank authorization contract

CURRENT provider `471b290` đã MAIN review và merge tại `5443123`.
15 targeted tests PASS trên PostgreSQL thật (Flyway V1), gồm ba review fixes.
Runtime consumer QB/Auth/RabbitMQ chưa được consolidated E2E xác minh.
Public routes/DTO cuối cùng phải được provider handoff và MAIN review trước Frontend.
Business scope: [parallel-participants-organization-scope.md](parallel-participants-organization-scope.md).

## Identity và boundary

Organization/Membership do `organization-service` sở hữu. `userId` là UUID Auth;
`organizationId`/`membershipId` là UUID riêng, không là User identity thứ hai.
Organization ACTIVE/ARCHIVED; membership ACTIVE/REVOKED/LEFT.
Roles OWNER/ADMIN/MEMBER; capability BANK_READ/BANK_USE/BANK_SUBMIT/BANK_REVIEW/BANK_MANAGE.
OWNER/ADMIN có cả năm, MEMBER có ba đầu; MEMBER có canReview được thêm BANK_REVIEW.
QB tự cấm review own contribution; permission không bypass ownership/version/snapshot.
System ADMIN, Organization ADMIN và Exam MANAGER/PROCTOR là các phạm vi khác nhau.

## REQUIRED internal API

```http
POST /organizations/internal/authorize
X-Service-Token: <ORG_AUTHZ_QB_TOKEN>
Content-Type: application/json

{"organizationId":"UUID","userId":"UUID","capability":"BANK_USE"}
```

```json
{
  "allowed": true,
  "organizationId": "UUID",
  "organizationStatus": "ACTIVE",
  "membershipId": "UUID",
  "membershipStatus": "ACTIVE",
  "role": "MEMBER",
  "decisionAt": "UTC ISO-8601"
}
```

Không tồn tại/inactive/non-member: allowed=false, nullable membership metadata.
Bad input/capability → 400; thiếu/sai/trùng service credential → 401.
Token dedicated chỉ cho QB, không dùng broad Exam/Notification/Auth contact token.
Không nhận Bearer hoặc X-User-* như authority tại endpoint nội bộ.
QB truyền actor từ JWT đã verify hoặc trusted Exam server identity đã verify,
không nhận actor từ client tự đặt. Request bound và timeout hợp lý.
Không cache allow. Unconfigured/error/timeout → chặn operation; không fallback
Teacher persona, systemPrivilege hoặc bank_authorities legacy.

Provider cũng cần bounded internal discovery `POST /organizations/internal/memberships`
với `{userId}` và cùng credential, trả active org memberships/capabilities để QB lọc
bank list mà không enumerate/query foreign DB. Shape đã handoff: request `{userId, capability?}`, response
`{userId, memberships, truncated, decisionAt}`. Mỗi item có organizationId,
organizationName, organizationStatus, membershipId, membershipStatus, role,
canReview, capabilities. Tối đa 200 memberships; `truncated=true` nghĩa kết quả
không đầy đủ, không được suy rằng user không thuộc một Org ngoài danh sách.
Operation authorization luôn gọi `/authorize` theo Org cụ thể.
Response authorize có thêm organizationName, canReview, capabilities; metadata
nullable có thể bị omit. ARCHIVED cho BANK_READ để đọc lịch sử; không cấp
BANK_USE/BANK_SUBMIT/BANK_REVIEW/BANK_MANAGE mới.
Không thay authorization operation bằng một membership list cache.

## Bank linkage

QB sở hữu bankId, contribution, publication, provenance, snapshots và eligibility.
Mỗi Organization Bank gắn organizationId thật; không auto-link legacy rows bằng tên.
Org quản lý membership; không chỉnh DB Bank. Bank creation/manage cần BANK_MANAGE;
submit cần BANK_SUBMIT; review cần BANK_REVIEW và không self-review; read/use cần
BANK_READ/BANK_USE. Inactive Organization/membership không eligible mới.
Already-published Exam snapshot vẫn bất biến dù Org archive/member bị revoke sau Publish.
Không xóa legacy grants/history; migration có mapping explicit hoặc fail closed.

## Observable acceptance

Owner/Admin, Member, delegated reviewer, non-member/revoked/left/archive;
self-review rejected; provider unavailable denies; no identity header spoof;
permission changes reflected on next operation; source snapshot/version preserved.

## Shared config do MAIN

Port 8088, database organization_db; module/service/Gateway wiring sau provider ready.
Gateway public `/organizations/**` xác minh access JWT; mọi internal route bị chặn
bởi security boundary hiện hữu. `.env.example` chỉ placeholders.

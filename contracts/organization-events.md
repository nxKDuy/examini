# Organization → Notification events

APPROVED v1 contract trong scope batch 2026-10-08. Producer handoff
`e0063b8` đang targeted review fixes, chưa merge; consumer chưa implement.
Không coi contract approval là bằng chứng runtime delivery.

Transport theo [notification.md](notification.md): durable topic `examini.events`,
routing key = eventType. Envelope `{eventId,eventType,eventVersion:1,occurredAt,payload}`.
UTC timestamp; stable eventId từ transactional outbox, publisher confirm/retry;
consumer dedup `(eventId, recipient)`, malformed/unsupported → DLQ.

Payload chung: recipientUserId (Auth UUID), organizationId (UUID),
organizationName (display text ≤120), link (SPA relative route). Consumer tự tạo
link theo type/ID và allowlist; không dùng arbitrary producer link làm redirect.
Không email, token, private note, grading/answer key.

| eventType | Người nhận | Payload thêm bắt buộc | Điều hướng |
| --- | --- | --- | --- |
| organization.invitation.created | người nhận lời mời | invitationId, role ADMIN/MEMBER, invitedByUserId, expiresAt UTC | /organizations/invitations |
| organization.invitation.accepted | người đã mời | invitationId, memberUserId | /organizations/{organizationId} |
| organization.invitation.declined | người đã mời | invitationId, declinedByUserId | /organizations/{organizationId} |
| organization.invitation.revoked | người nhận lời mời | invitationId | /organizations/invitations |
| organization.invitation.expired | người nhận lời mời | invitationId | /organizations/invitations |
| organization.membership.revoked | thành viên bị revoke | membershipId | /organizations |
| organization.membership.role-changed | thành viên | membershipId, role ADMIN/MEMBER | /organizations/{organizationId} |
| organization.ownership.transferred | OWNER mới | membershipId | /organizations/{organizationId} |

Notification chỉ chuyển business fact thành wording tiếng Việt/inbox. Giữ existing
auth, preferences, delivery mechanisms; không tạo reminder/campaign mới. In-app
consumer là acceptance tối thiểu của batch. Email/push chỉ dùng pipeline hiện hữu
nếu phù hợp, không claim tất cả kênh trước khi xác minh.

Frontend phải phân biệt Organization role và systemPrivilege. Revoked recipient
vẫn xem được inbox own; resource route vẫn chịu Organization authorization mới.
Producer không chờ Notification hoặc truy cập DB Notification.

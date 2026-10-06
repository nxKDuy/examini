# Notification → Auth contact

## CURRENT — provider đã review và tích hợp MAIN

Provider commits: `779d9f4638cfd9781f1a1168b00791261a964db6` và
`4b0f90205db7412bcc4879ff2356fb4bd279eadc`.
MAIN integration: `8fc83ad61860dde6f791e4df1cc117261494cb43`.
Không phải API public; Notification gọi trực tiếp Auth trên service network.

```text
POST /auth/internal/contacts
X-Service-Token: <NOTIFICATION_CONTACT_TOKEN>
Content-Type: application/json

{"userIds": ["<UUID>"]}

200 [{"userId": "<UUID>", "email": "<email persisted>", "displayName": "<fullName persisted>"}]
```

- 1..100 UUID không null, body tối đa 16 KiB. Chia lô lớn ở consumer.
- Chỉ trả ID tồn tại, bỏ ID trùng, giữ thứ tự yêu cầu; ID không tồn tại bị bỏ qua.
- Một truy vấn batch, không cache/replica liên hệ lâu dài ở consumer.
- Không có `active`/`emailVerified`: Auth hiện không sở hữu các field này.
  Không suy diễn chúng từ role/persona. Không trả authority/password/token.
- Credential riêng, không dùng JWT hoặc token Question Bank/Exam thay thế.
  Thiếu/sai/rỗng/trùng service header hoặc token cấu hình rỗng: 401 fail closed.
  Có Authorization cũng bị từ chối kể cả có service token đúng; consumer không
  gửi Bearer. X-User-* không là identity authority cho endpoint này.
- Body sai/UUID sai/limit sai: 400; body quá lớn: 413. Xác thực trước đọc body.
- Không log credential, body hay email; không echo chúng trong lỗi.

## Gateway / config

Gateway commit `035a0e4` chặn segment `internal` trước public-auth/JWT forwarding,
kể cả route discovery prefix, encoding, dấu slash/matrix parameters. Traffic
public không forward X-Service-Token hay X-Question-Owner-Id. Notification không
gọi API này qua Gateway. Auth vẫn kiểm credential riêng nếu gọi trực tiếp.

Compose Auth nhận NOTIFICATION_CONTACT_TOKEN từ local env; `.env.example` chỉ
placeholder rỗng. Consumer wiring được MAIN xử lý khi Notification được review.
Không đưa sender email hoặc app password vào Git/handoff.

## Bằng chứng và giới hạn

- Auth owner (Claude Opus 5.5): Docker JDK21, PostgreSQL test riêng, Maven
  `mvn -B -pl auth-service -am test`: 37 tests, 0 lỗi, 0 skipped;
  gồm persistence thật/Flyway V1–V4. MAIN review source và XML Surefire đối chiếu.
- Gateway MAIN: archive committed MAIN + exact Gateway patch, Docker JDK21,
  `mvn -B -pl api-gateway -am test`: 10 tests, 0 lỗi, 0 skipped.
- MAIN real smoke: JAR từ committed MAIN ad81a84, Auth/Gateway/Redis/PostgreSQL
  riêng, 23 checks PASS: register Student/Teacher, signed identity dù spoof headers,
  Admin bootstrap/login, ADMIN/SYSTEM_ADMIN registration denied, privilege injection
  không cấp ADMIN, refresh rejected, private contact minimal response/credential,
  Gateway deny encoded/internal/discovery-prefix variants và Flyway V1–V4.
  Không fixture backend, không dùng dữ liệu service đang chạy. Cấu hình route test
  cô lập đã sửa trước lượt PASS; không đổi runtime config production để test qua.
- Chưa có E2E Notification → Auth, Scheduling hoặc Gmail delivery. SMTP TLS/login
  PASS không chứng minh email đã gửi; chưa xác nhận release Scheduling.

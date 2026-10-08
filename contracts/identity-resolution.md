# Auth → Exam/Organization identity resolution

CURRENT provider đã MAIN review và merge tại `0daa663`. Auth owner triển khai;
không cấp token Notification contact cho domain khác, không query Auth DB trực tiếp.

```http
POST /auth/internal/identities/resolve
X-Service-Token: <EXAM_IDENTITY_TOKEN hoặc ORGANIZATION_IDENTITY_TOKEN>

{"userIds":["UUID"]}
```

Hoặc `{ "emails": ["existing-user@example.test"] }`; đúng một loại selector,
1..100 exact values; validate input/body size, batch query/dedup, bỏ missing identities.
Response array `{userId, displayName, email, persona}` từ persisted Auth state.
Không fuzzy global directory, password, JWT, permissions hoặc systemPrivilege.
Missing/sai/trùng credential hoặc có Bearer → 401; config rỗng fail closed;
không chấp nhận Notification/QB/Exam broad token; không log/echo PII/secret.

Consumer: Exam chỉ assign STUDENT; Organization invite existing recipientUserId,
accept bằng signed JWT đúng recipient. Không suy emailVerified/active.
Frontend chỉ qua scoped consumer public API, không gọi endpoint internal.
Gateway existing internal-deny phải tiếp tục chặn route này.

## Validation provider

Worker chạy `mvn test` với JDK 21: 41 PASS, 1 persistence SKIP do chưa có
`AUTH_TEST_DATABASE`. MAIN review diff và Surefire XML; runtime resolver trên
PostgreSQL thật sẽ được xác minh trong consolidated E2E của batch.
Emails được so khớp chính xác như lưu tại Auth, không lowercase selector tại consumer.

## Consumer Question Bank (APPROVED 2026-10-08; provider merged `ef1a62b`)

Contract Request của QB: credential riêng `QUESTION_BANK_IDENTITY_TOKEN`, cùng endpoint và
shape. QB chỉ snapshot `displayName` tác giả cho Public publication, không lưu/log email;
thiếu token hoặc Auth lỗi → fail closed (không publish với tên giả). Auth owner triển khai
(task_54b7a4c7d795); MAIN wiring env khi merge.

# Auth → Exam/Organization identity resolution

REQUIRED provider request, chưa implement. MAIN giao Auth owner riêng;
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

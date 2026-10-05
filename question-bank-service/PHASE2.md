# Question Bank Phase 2 — Multi-bank foundation

Phạm vi: backend, database, API và tests. Tài liệu này mô tả provider mới để
MAIN review; không phải tuyên bố đã merge, đã expose Gateway hoặc đã triển khai
frontend. Phần Phase 1 kế thừa từ committed MAIN `966cd35` đã runtime-pass.
`contracts/**` vẫn là tài liệu chung do MAIN sở hữu; không sửa trong commit này.
Các phần FUTURE của PHASE1.md về bank được cụ thể hóa bởi tài liệu này.

## Mô hình

- `QuestionBank`: id, type (`PERSONAL`, `ORGANIZATION`, `SYSTEM`, `PUBLIC`),
  ownerUserId hoặc organizationId, name, status, defaultBank, createdAt.
- `Question.bankId`: reference local bank, bất biến trong API. `createdBy` tiếp
  tục là owner của PERSONAL và là tác nhân tạo nội dung SYSTEM; không biến
  người tạo SYSTEM thành chủ sở hữu cá nhân của bank.
- `PublicContribution`: sourceQuestionId/version/bank/owner, submittedBy/time,
  immutable full snapshot, trạng thái xác minh, reviewer/note/time.
- `PublicQuestion`: publication riêng với ID riêng, bank PUBLIC, contributionId,
  sourceQuestionId/version/bank/owner và full snapshot bất biến.
- `QuestionLineage`: nguồn trực tiếp của fork gồm sourceQuestionId/version,
  sourceBankId/type, optional sourcePublicQuestionId, copiedBy/time.

QuestionScope vẫn chỉ có PERSONAL/ORGANIZATION/SYSTEM. PUBLIC không trở thành
scope mutable của Question. `PublicQuestion.id` khác `question.id`; field
`question` bên trong PublicResponse là snapshot nguồn, nên `question.id` và
`question.questionVersion` mô tả source. Consumer dùng publication id bên ngoài
để chọn/fork, tuyệt đối không coi nó là ID đủ điều kiện cho Exam.

### PERSONAL

Nhiều bank có tên cho một owner; một bank default cho mỗi owner để API cũ và CSV
hoạt động như trước. Migration gán Questions cũ theo `createdBy`, không đổi UUID,
owner, status, revision, options hoặc lịch sử. Provision bank default mới dùng
INSERT ON CONFLICT với unique partial index; không có rewrite ownership.

### ORGANIZATION

Chỉ provision reference bởi signed systemAdmin: organizationId không phải
membership và không được xác minh tồn tại bởi domain này. Trạng thái cố định
`PENDING_AUTHORIZATION`, không fake organization/membership. SystemAdmin được
đọc metadata để phối hợp; nội dung/list/fork/authoring vẫn đóng đến khi có
Organization authorization contract. Operational request trả 503 nếu đã đến
resource được provision; caller không có quyền biết resource trả 404/403.

### SYSTEM

Bank không có ownerUserId. Provision/create/edit/submit/review/archive yêu cầu
signed `systemPrivilege=ADMIN` và role tương thích. TEACHER không có quyền toàn
hệ thống; legacy ADMIN thiếu explicit claim cũng không được cấp quyền Phase 2.
Question trong SYSTEM vẫn DRAFT -> PENDING_REVIEW -> APPROVED/REJECTED và có
version snapshots như Phase 1. User khác chỉ đọc safe APPROVED, có thể fork vào
PERSONAL nếu được phép author; không sửa source SYSTEM.

### PUBLIC và contribution

PERSONAL owner gửi **current APPROVED version** -> PUBLIC_PENDING. Quyết định
public là độc lập với review Question: không tự động xác minh từ APPROVED.
Explicit systemAdmin -> PUBLIC_VERIFIED hoặc PUBLIC_REJECTED; reject bắt buộc
note. Terminal decision không được ghi đè. Mỗi source/version có một submission;
muốn gửi lại sau rejection phải sửa và duyệt version mới ở source.
Chỉ PUBLIC_VERIFIED tạo PublicQuestion trong cùng transaction. Row locks ngăn
submission/review race; unique constraints chống publication trùng.

Snapshot contribution được lấy từ immutable QuestionVersion lúc submit; source
đổi version hoặc bị archive sau đó không thay đổi dữ liệu đang review/public.
Reviewer quyết định trên captured approved version, không tự chuyển sang source
mới. Source owner vẫn sở hữu Question; PublicQuestion không chuyển quyền source.
Không có API sửa/xóa PublicQuestion hoặc đổi ownership trong Phase 2.

### Copy/fork

Một capability `fork` bao phủ copy độc lập, không có branching/version-control.
PERSONAL source yêu cầu đọc theo ownership/privileged read policy Phase 1;
SYSTEM source yêu cầu current APPROVED version; PUBLIC source phải là verified
publication. Target chỉ là PERSONAL của author hiện tại; body không nhận owner.
Organization source/target chưa mở.
Fork tạo Question mới, option UUIDs mới, version1/DRAFT, creationMethod=COPIED,
full snapshot và immutable first-hop lineage. PUBLIC/SYSTEM fork có sourceType
PUBLIC_BANK/SYSTEM_BANK và sourceRefId tương ứng; PERSONAL copy giữ knowledge
provenance cũ, lineage lưu riêng origin copy. Chỉnh sửa fork không đổi source,
chỉnh sửa source không đổi fork. Không chỉ lưu sourceQuestionId mà thiếu version.
Fork không tự APPROVED và không tự vào Exam pool.

## Contract delta — routes và DTO

Tất cả routes cần validated access Bearer JWT; không anonymous hoặc trusted
identity headers. Internal service token Phase 1 không dùng được các route mới.
Pagination: `page`, `size` (default20/max100), `sort`; enum/UUID phải hợp lệ.

| Route | Input | Output / quyền |
| --- | --- | --- |
| GET `/question-banks` | type?, ownerUserId?, organizationId?, page/size/sort | Page<BankResponse>; own PERSONAL + SYSTEM/PUBLIC; systemAdmin có metadata ORGANIZATION |
| POST `/question-banks` | `{type,name,organizationId?}` | 201 BankResponse; author tạo own PERSONAL; systemAdmin provision SYSTEM/ORGANIZATION; PUBLIC default chỉ migration provision |
| GET `/question-banks/{id}` | UUID | BankResponse theo resource policy |
| GET `/question-banks/{id}/questions` | questionType?, difficulty?, status?, page/size/sort | Page<BankQuestion>, luôn safe; SYSTEM nonadmin chỉ APPROVED; ORGANIZATION blocked; PUBLIC dùng public route dưới |
| POST `/question-banks/{id}/questions` | CreateRequest Phase 1, scope optional phải khớp bank | 201 BankQuestion với author data; owner PERSONAL hoặc explicit systemAdmin SYSTEM |
| GET `/questions/{id}/lineage` | UUID | LineageResponse theo Question read policy; root chưa fork trả 404 |
| POST `/questions/{id}/fork` | `{sourceQuestionVersion,targetBankId?}` | 201 BankQuestion; author, target own PERSONAL/default; source quyền như trên |
| POST `/questions/{id}/contributions` | `{sourceQuestionVersion}` | 201 ContributionResponse safe; PERSONAL owner/current APPROVED |
| GET `/public-contributions` | status?, page/size/sort | Page<ContributionResponse> safe; submitter thấy own, explicit reviewer thấy tất cả |
| GET `/public-contributions/{id}` | includeAnswers defaultfalse | ContributionResponse safe; submitter/reviewer; true chỉ explicit systemAdmin |
| POST `/public-contributions/{id}/review` | `{decision:APPROVED\|REJECTED,reviewNote?}` | ContributionResponse safe; explicit systemAdmin; terminal conflict409 |
| GET `/public-questions` | bankId?, sourceOwnerUserId?, questionType?, difficulty?, page/size/sort | Page<PublicResponse>, safe verified publications |
| GET `/public-questions/{id}` | publication UUID | PublicResponse safe |
| POST `/public-questions/{id}/fork` | `{sourceQuestionVersion,targetBankId?}` | 201 independent own PERSONAL BankQuestion, author data |

`sourceQuestionVersion` bắt buộc positive; Public fork phải khớp version của
publication. Request body vẫn dùng `questionType`, `difficultyLevel` của
CreateRequest; query filter giữ tên `difficulty` theo convention hiện tại.

DTOs:

- BankResponse: id/type/ownerUserId/organizationId/name/status/defaultBank/createdAt.
- BankQuestion: `{bankId, question: QuestionProjection}`; giữ QuestionResponse
  cũ không đổi shape để Exam và frontend Phase 1 không phải đổi.
- ContributionResponse: id, sourceQuestionId, sourceQuestionVersion, sourceBankId,
  sourceOwnerUserId, submittedBy, submittedAt, status, reviewerId, reviewNote,
  reviewedAt và question snapshot. Default/list safe; reviewer explicit true full.
- PublicResponse: publication id, bankId, sourceQuestionId/version/bank/owner,
  PUBLIC_VERIFIED, verifiedBy/time, safe source question snapshot.
- LineageResponse: questionId, sourceQuestionId/version, sourceBankId/type,
  sourcePublicQuestionId?, copiedAt, copiedBy.

### Auth matrix và đáp án

| Capability | Owner author | TEACHER khác | STUDENT | Explicit systemAdmin | Internal Phase1 |
| --- | --- | --- | --- | --- | --- |
| Own PERSONAL create/edit/submit/archive | Có | Không | Không | Chỉ own authoring | Không |
| SYSTEM provision/mutate/review | Không | Không | Không | Có | Không |
| Public safe listing/detail | Có | Có | Có | Có | Không |
| Submit contribution | Own current APPROVED | Không | Không | Chỉ own PERSONAL | Không |
| Public review/key read | Không | Không | Không | Có | Không |
| Fork verified Public/approved System | Own PERSONAL target | Own PERSONAL target | Không | Own PERSONAL target | Không |
| Organization content | Blocked | Blocked | Blocked | Blocked | Không |

Default reads/listing loại bỏ `correct`, `expectedAnswer`, `sourceExcerpt` bằng
SafeQuestionResponse riêng. Không trả JPA entities. Full contribution snapshot
chỉ ở reviewer GET includeAnswers=true. Public reads không có answer-bearing
variant. Fork/create trả author-only data, gồm đáp án của **Question mới thuộc
caller**; đây là khả năng authoring được phép, không phải student read lane.
Không gửi rubric/key vào student/session DTOs. Dữ liệu visible do tác giả tự
viết có thể chứa đáp án; review vẫn chịu trách nhiệm kiểm tra nội dung.

Auth JWT signer hiện có claim systemPrivilege NONE/ADMIN. Provider kiểm tra
chữ ký/expiry/type/UUID/recognized role và explicit privilege nếu có; claim lệch
role/privilege bị từ chối. Headers X-System-Privilege/X-User-* không được tin.
Legacy signed role chỉ giữ hành vi Phase 1, không cấp authority mới ở Phase 2.
Không tự thêm global permission, Organization/Auth service hoặc DB access.

## Exam compatibility

GET random và POST eligible batch vẫn **PERSONAL + accessible + APPROVED**,
UUID[]/DTO identity `{id,questionVersion}`, strict ordering/errors và answer-key
lane không đổi. SYSTEM, PublicQuestion, pending/rejected contributions không
lọt vào official pool. Muốn dùng dữ liệu bank khác: fork vào PERSONAL, submit,
duyệt lại, sau đó dùng contract Phase 1. Publish vẫn giữ snapshot ở Exam domain;
worker không sửa Exam/Session/Grading hay gọi DB của các service đó.
Mở direct cross-bank Exam selection là contract riêng trong tương lai, không
âm thầm nới eligibility ở task này.

## Migration V3

Thêm 4 tables: question_banks/public_contributions/public_questions/question_lineage,
question.bank_id NOT NULL, backfill owner và bank default. Composite FK bảo đảm
bank type khớp Question scope, PublicQuestion thuộc PUBLIC bank, lineage nguồn
khớp bank type. FK sourceQuestionId/version giữ snapshot được tham chiếu; unique
source/version contribution và publication/contribution. Index bank/status,
organization reference, public filters, contribution submitter/status và lineage.
Không drop/recreate application tables; không sửa V1/V2 hoặc JSON snapshots cũ.
Nếu database có non-PERSONAL rows ngoài Phase 1 chuẩn, V3 từ chối migration để
MAIN lập mapping ownership tường minh, không tự suy hoặc mất dữ liệu.

## Tests và runtime

51 tests: 26 core unit,7 authentication,8 Phase1 PostgreSQL/MockMvc,10 Multi-bank
PostgreSQL/MockMvc. Bao gồm V2->V3 upgrade với approved source version2/hai
snapshots/review/option IDs, ownership, organization fail-closed, explicit system
privilege, contribution pending/rejection/publication, fork source version và
independence, safe payloads, paging/filter, concurrency, Exam exclusions.
`QB_TEST_DATABASE=true` và datasource test riêng bắt buộc để chạy persistence
suite; nếu không có flag, suite đó intentionally skip, không claim integration pass.

```sh
mvn -pl question-bank-service -am verify
QB_PHASE2_URL=http://127.0.0.1:18092 python3 question-bank-service/src/test/phase2_api_smoke.py
```

Runtime smoke dùng packaged JDK21 service, PostgreSQL16 riêng, synthetic signed
identities, loopback-only; không deployment/main gateway/browser test. Script
không in tokens/keys, không đọc foreign DB. Xem final report cho exact Docker
command/log/result. Frontend không sửa/test/build trong task backend này.

## Contract Requests và rollout gate

QB2-ORG-AUTH yêu cầu resource-scoped authorize(userId,organizationId,action) từ
Organization provider; provider hiện chưa được giao implementation. MAIN xác
nhận fail-closed và chặn org operational access ở msg_084efa5cd0c9/msg_e0c34981c58f.
Additive routes/auth matrix/DTO ở đây còn cần MAIN review trước runtime exposure.
Gateway hiện chưa có routing `/question-banks/**`, `/public-questions/**`,
`/public-contributions/**`; MAIN sở hữu việc review/routing. Worker không sửa
Gateway, Auth, shared docs/config, dependencies hay frontend.
NOTIF-QB-REVIEWED là request riêng: MAIN đã phê duyệt envelope/transport/outbox
ở MAIN5783207 (msg_2722d96d7d57), nhưng provider event/outbox chưa implement,
MAIN sẽ giao task riêng. Không mở rộng Phase2 sang Notification. Không dùng
settled dispatch để gửi completion. MAIN có thể có commit mới trong lúc worker
làm việc; base đã sync trước implementation vẫn là966cd35, không lấy dirty MAIN.

## GRAPHIFY DELTA cho MAIN

Đã đọc canonical snapshot main: commit6ab87d4, dirty=true, built04:19:32Z
2026-10-05; cũ hơn synced MAIN966cd35 nên chỉ dùng để định hướng source.
Theo contracts/shared-graph.md, MAIN build canonical sau merge bằng tool repo
hiện có (`scripts/shared_graph.py build` qua runtime trong common Git dir).
Không tạo format graph riêng hoặc ghi vào canonical outputs từ worker.

Các nodes/relationships cần kiểm tra sau refresh:

- Question -> QuestionBank (bankId); PERSONAL owner, ORGANIZATION organizationId
  reference/PENDING_AUTHORIZATION, SYSTEM explicit authority, PUBLIC publication.
- MultiBankController -> MultiBankService -> BankRegistry/QuestionPolicy;
  QuestionAuthentication -> signed systemPrivilege -> QuestionActor.systemAdmin.
- PublicContribution -> QuestionVersion immutable snapshot, source bank/owner;
  Public review -> PublicQuestion publication, independent verification lifecycle.
- PublicQuestion -> sourceQuestionId/version + full snapshot; không live fetch
  vào source khi đọc publication. QuestionLineage -> source version/bank/publicId.
- Fork -> QuestionService.copySnapshot -> own PERSONAL DRAFT/version1/options mới;
  source/public/fork độc lập, copied provenance không trộn knowledge source.
- Exam QuestionBankClient -> legacy random/batch -> PERSONAL APPROVED restriction;
  published Exam/Session/Grading giữ snapshot trong domain riêng.
- Organization authorization dependency là CONTRACT REQUEST blocked, không có
  membership entity giả hay call foreign DB để graph hiểu nhầm capability.

AST extraction thể hiện code edges; ownership/HTTP/eligibility business invariant
phải đối chiếu tài liệu này và contracts, không coi inferred edge là contract.

## Ngoài phạm vi

Không frontend, stars/rating/comments/abuse/reputation, AI quality/recommendation,
PDF/Word/OCR ingestion, web crawl, chunking, membership management, notification
transport/outbox, hoặc direct cross-bank Exam contract. Không merge/push MAIN.

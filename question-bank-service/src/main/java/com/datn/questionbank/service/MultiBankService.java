package com.datn.questionbank.service;

import com.datn.questionbank.dto.BankDto.*;
import com.datn.questionbank.dto.QuestionDto.*;
import com.datn.questionbank.entity.*;
import com.datn.questionbank.repository.*;
import com.datn.questionbank.security.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import static com.datn.questionbank.entity.QuestionBank.BankType;
import static com.datn.questionbank.entity.PublicContribution.VerificationStatus;

@Service @RequiredArgsConstructor
@Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
public class MultiBankService {
    private final QuestionBankRepository banks;
    private final BankRegistry registry;
    private final QuestionRepository questions;
    private final QuestionVersionRepository versions;
    private final QuestionLineageRepository lineage;
    private final PublicContributionRepository contributions;
    private final PublicQuestionRepository publications;
    private final PublicQuestionItemRepository publicItems;
    private final CategoryRepository categories;
    private final SubjectRepository subjects;
    private final OrganizationQuestionRepository organizationQuestions;
    private final QuestionService core;
    private final QuestionPolicy policy;
    private final OrganizationAuthorizationClient organizationAuthorization;
    private final AuthIdentityClient identities;
    private final ObjectMapper json;
    private final NamedParameterJdbcTemplate jdbc;
    private final EntityManager entityManager;

    @Transactional
    public BankResponse createBank(CreateBank request,QuestionActor actor) {
        if(request.type()==BankType.PERSONAL) {
            policy.requireAuthor(actor);
            if(request.organizationId()!=null) throw bad("PERSONAL không dùng organizationId");
        } else if (request.type()==BankType.ORGANIZATION) {
            if(request.organizationId()==null) throw bad("ORGANIZATION yêu cầu organizationId");
            registry.requireOrganizationManage(request.organizationId(), actor);
        } else {
            systemAuthority(actor);
            if(request.type()==BankType.PUBLIC) throw bad("Public Bank mặc định được provision bởi migration");
            if((request.type()==BankType.ORGANIZATION)!=(request.organizationId()!=null)) throw bad("organizationId không khớp bank type");
        }
        var bank=QuestionBank.builder().type(request.type()).name(request.name().trim())
                .ownerUserId(request.type()==BankType.PERSONAL?actor.userId():null).organizationId(request.organizationId())
                .status(QuestionBank.BankStatus.ACTIVE)
                .createdAt(LocalDateTime.now()).build();
        return bankResponse(banks.save(bank));
    }
    public Page<BankResponse> listBanks(BankType type,UUID owner,UUID organization,Pageable page,QuestionActor actor) {
        bounds(page);
        var organizationIds=(type==null||type==BankType.ORGANIZATION)
                ?organizationAuthorization.memberships(actor.userId(),OrganizationAuthorizationClient.Capability.BANK_READ)
                    .stream().map(OrganizationAuthorizationClient.Membership::organizationId).toList()
                :List.<UUID>of();
        Specification<QuestionBank> spec=(r,q,b)->b.or(
                b.and(b.equal(r.get("type"),BankType.PERSONAL),b.equal(r.get("ownerUserId"),actor.userId())),
                r.get("type").in(BankType.SYSTEM,BankType.PUBLIC),
                b.and(b.equal(r.get("type"),BankType.ORGANIZATION),r.get("organizationId").in(organizationIds)));
        if(type!=null) spec=spec.and((r,q,b)->b.equal(r.get("type"),type));
        if(owner!=null) spec=spec.and((r,q,b)->b.equal(r.get("ownerUserId"),owner));
        if(organization!=null) spec=spec.and((r,q,b)->b.equal(r.get("organizationId"),organization));
        return banks.findAll(spec,page).map(this::bankResponse);
    }
    public BankResponse bank(UUID id,QuestionActor actor) {var b=registry.get(id);registry.requireRead(b,actor);return bankResponse(b);}
    public Page<BankQuestion> listQuestions(UUID bankId,Question.QuestionType type,Question.DifficultyLevel difficulty,
            Question.QuestionStatus status,Pageable page,QuestionActor actor) {
        bounds(page);var bank=registry.get(bankId);registry.requireRead(bank,actor);
        if(bank.getType()==BankType.ORGANIZATION) throw bad("Organization Bank dùng GET /organization-questions?bankId=...");
        if(bank.getType()==BankType.PUBLIC) throw bad("Public Bank dùng GET /public-questions");
        Specification<Question> spec=(r,q,b)->b.equal(r.get("bankId"),bankId);
        if(bank.getType()==BankType.SYSTEM&&!actor.systemAdmin()) spec=spec.and((r,q,b)->b.equal(r.get("status"),Question.QuestionStatus.ACTIVE));
        if(type!=null)spec=spec.and((r,q,b)->b.equal(r.get("questionType"),type));
        if(difficulty!=null)spec=spec.and((r,q,b)->b.equal(r.get("difficultyLevel"),difficulty));
        if(status!=null)spec=spec.and((r,q,b)->b.equal(r.get("status"),status));
        return questions.findAll(spec,page).map(q->new BankQuestion(bankId,core.findById(q.getId(),false,actor)));
    }
    @Transactional
    public BankQuestion createQuestion(UUID bankId,CreateRequest request,QuestionActor actor) {
        var bank=registry.get(bankId);
        if(bank.getType()==BankType.ORGANIZATION||bank.getType()==BankType.PUBLIC)
            throw bad("ORGANIZATION/PUBLIC chỉ nhận immutable publication qua contribution/publish flow");
        return new BankQuestion(bankId,core.createInBank(request,bankId,actor));
    }
    public LineageResponse lineage(UUID id,QuestionActor actor) {
        var copy=core.findById(id,false,actor);
        var l=lineage.findById(id).orElseThrow(()->missing("Không có lineage cho câu hỏi gốc"));
        var nodes=new ArrayList<LineageNode>();
        var publication=lineagePublication(l,actor);
        lineageSource(l,publication,actor).ifPresent(nodes::add);
        publication.ifPresent(nodes::add);
        nodes.add(new LineageNode(LineageRelation.COPY,copy.id(),copy.questionVersion(),copy.bankId(),copy.bankType(),
                copy.status().name(),bankName(copy.bankId()),true,false));
        return new LineageResponse(l.getQuestionId(),l.getSourceQuestionId(),l.getSourceQuestionVersion(),l.getSourceBankId(),
                l.getSourceBankType(),l.getSourcePublicQuestionId(),l.getSourceOrganizationQuestionId(),l.getCopiedAt(),l.getCopiedBy(),nodes);
    }
    @Transactional
    public BankQuestion forkQuestion(UUID id,ForkRequest request,QuestionActor actor) {
        policy.requireAuthor(actor);
        var source=questions.findForUpdate(id).orElseThrow(()->missing("Không tìm thấy câu hỏi"));
        policy.requireRead(source,actor);
        if(source.getScope()==Question.QuestionScope.ORGANIZATION)
            throw bad("Organization fork dùng immutable organization publication");
        if(source.getScope()==Question.QuestionScope.SYSTEM&&(source.getStatus()!=Question.QuestionStatus.ACTIVE||source.getQuestionVersion()!=request.sourceQuestionVersion()))
            throw conflict("System fork chỉ từ current ACTIVE version");
        var bank=registry.get(source.getBankId());
        return copy(snapshot(id,request.sourceQuestionVersion()),request.targetBankId(),bank.getId(),bank.getType(),null,actor);
    }
    @Transactional
    public BankQuestion forkPublic(UUID id,ForkRequest request,QuestionActor actor) {
        policy.requireAuthor(actor);
        var p=publication(id);
        if(p.getPostType()!=PublicQuestion.PostType.SINGLE_QUESTION) throw conflict("Question Set dùng POST /public-questions/{id}/fork-set");
        if(p.getStatus()!=PublicQuestion.PublicationStatus.PUBLISHED) throw conflict("PublicQuestion đã archive");
        if(p.getSourceQuestionVersion()!=request.sourceQuestionVersion()) throw conflict("Public source version không khớp");
        var result=copy(read(p.getSnapshot()),request.targetBankId(),p.getBankId(),BankType.PUBLIC,p.getId(),actor);recordCopy(p,actor);return result;
    }
    @Transactional public BankQuestion forkPublicItem(UUID postId,UUID itemId,ForkRequest request,QuestionActor actor) {
        policy.requireAuthor(actor);var p=publication(postId);
        if(p.getStatus()!=PublicQuestion.PublicationStatus.PUBLISHED)throw conflict("Public post đã archive");
        var item=publicItems.findById(itemId).orElseThrow(()->missing("Không tìm thấy public item"));
        if(!item.getPublicationId().equals(postId))throw missing("Không tìm thấy public item");
        if(item.getSourceQuestionVersion()!=request.sourceQuestionVersion())throw conflict("Public source version không khớp");
        var result=copy(read(item.getSnapshot()),request.targetBankId(),p.getBankId(),BankType.PUBLIC,postId,actor);recordCopy(p,actor);return result;
    }
    void recordCopy(PublicQuestion post,QuestionActor actor) {
        if(!post.getSourceOwnerUserId().equals(actor.userId()))jdbc.update("INSERT INTO public_question_copy_events(publication_id,actor_user_id,copied_at) VALUES(:post,:actor,timezone('UTC',CURRENT_TIMESTAMP))",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("post",post.getId()).addValue("actor",actor.userId()));
    }
    private BankQuestion copy(QuestionResponse snapshot,UUID target,UUID sourceBank,BankType type,UUID publicId,QuestionActor actor) {
        UUID targetBank=target==null?registry.defaultPersonal(actor.userId()):target;
        var bank=registry.get(targetBank);registry.requireWrite(bank,actor);
        if(bank.getType()!=BankType.PERSONAL) throw bad("Fork target phải là PERSONAL");
        var sourceType=switch(type){case PUBLIC->Question.SourceType.PUBLIC_BANK;case SYSTEM->Question.SourceType.SYSTEM_BANK;
            case ORGANIZATION->Question.SourceType.ORGANIZATION_BANK;case PERSONAL->snapshot.sourceType();};
        String reference=type==BankType.PERSONAL?snapshot.sourceRefId():publicId==null?snapshot.id().toString():publicId.toString();
        var copy=core.copySnapshot(snapshot,targetBank,sourceType,reference,actor);
        lineage.save(QuestionLineage.builder().questionId(copy.id()).sourceQuestionId(snapshot.id())
                .sourceQuestionVersion(snapshot.questionVersion()).sourceBankId(sourceBank).sourceBankType(type)
                .sourcePublicQuestionId(publicId).copiedAt(LocalDateTime.now()).copiedBy(actor.userId()).build());
        return new BankQuestion(targetBank,copy);
    }
    @Transactional
    public PublicResponse publish(UUID id,ContributionRequest request,QuestionActor actor) {
        var q=questions.findForUpdate(id).orElseThrow(()->missing("Không tìm thấy câu hỏi"));
        policy.requireOwner(q,actor);
        if(q.getScope()!=Question.QuestionScope.PERSONAL) throw bad("Publish source phải là PERSONAL");
        if(q.getStatus()!=Question.QuestionStatus.ACTIVE||q.getQuestionVersion()!=request.sourceQuestionVersion())
            throw conflict("Publish yêu cầu current ACTIVE source version");
        var existing=contributions.findBySourceQuestionIdAndSourceQuestionVersion(id,request.sourceQuestionVersion());
        if(existing.isPresent()) {
            var previous=publications.findByContributionId(existing.get().getId());
            if(previous.isPresent()) {
                if(previous.get().getStatus()==PublicQuestion.PublicationStatus.ARCHIVED)
                    throw conflict("Version này đã unpublish; sửa nguồn để tạo version mới rồi publish lại");
                return publicResponse(previous.get(),actor);
            }
            throw conflict("Legacy contribution cần version mới để publish; lịch sử vẫn được giữ");
        }
        var now=LocalDateTime.now();
        var frozen=snapshot(id,request.sourceQuestionVersion()).withContext(Question.QuestionStatus.ACTIVE,q.getUpdatedAt());
        // Existing audit rows/FKs are retained; this is direct publication, without a review decision.
        var c=contributions.save(PublicContribution.builder().sourceQuestionId(id).sourceQuestionVersion(request.sourceQuestionVersion())
                .sourceBankId(q.getBankId()).sourceOwnerUserId(q.getCreatedBy()).submittedBy(actor.userId()).submittedAt(now)
                .snapshot(write(frozen)).status(VerificationStatus.PUBLIC_PUBLISHED).build());
        var p=publications.save(PublicQuestion.builder().bankId(banks.findByTypeAndDefaultBankTrue(BankType.PUBLIC).orElseThrow().getId())
                .contributionId(c.getId()).sourceQuestionId(id).sourceQuestionVersion(q.getQuestionVersion())
                .sourceBankId(q.getBankId()).sourceOwnerUserId(q.getCreatedBy()).sourceOwnerDisplayName(identities.displayName(q.getCreatedBy()))
                .snapshot(c.getSnapshot()).searchContent(frozen.content()).status(PublicQuestion.PublicationStatus.PUBLISHED)
                .questionType(frozen.questionType()).difficultyLevel(frozen.difficultyLevel()).verifiedBy(actor.userId()).verifiedAt(now)
                .title(frozen.content().substring(0,Math.min(200,frozen.content().length()))).categoryId(frozen.categoryId())
                .subjectId(q.getCategory().getSubject().getId()).itemCount(1).postType(PublicQuestion.PostType.SINGLE_QUESTION).build());
        publicItems.save(PublicQuestionItem.builder().id(p.getId()).publicationId(p.getId()).position(0).sourceQuestionId(q.getId())
                .sourceQuestionVersion(q.getQuestionVersion()).sourceBankId(q.getBankId()).sourceOwnerUserId(q.getCreatedBy())
                .snapshot(c.getSnapshot()).searchContent(frozen.content()).questionType(frozen.questionType()).difficultyLevel(frozen.difficultyLevel())
                .categoryId(frozen.categoryId()).subjectId(q.getCategory().getSubject().getId()).categoryName(q.getCategory().getName())
                .subjectName(q.getCategory().getSubject().getName()).categoryParentId(q.getCategory().getParent()==null?null:q.getCategory().getParent().getId())
                .categoryPath(writeCategoryPath(q.getCategory())).build());
        return publicResponse(p,actor);
    }
    public ContributionResponse contribute(UUID id,ContributionRequest request,QuestionActor actor) {
        throw new ResponseStatusException(HttpStatus.GONE,"Public review đã bỏ; dùng POST /questions/{id}/publish-public");
    }
    public Page<ContributionResponse> listContributions(VerificationStatus status,Pageable page,QuestionActor actor) {
        bounds(page);Specification<PublicContribution> spec=(r,q,b)->actor.systemAdmin()?b.conjunction():b.equal(r.get("submittedBy"),actor.userId());
        if(status!=null)spec=spec.and((r,q,b)->b.equal(r.get("status"),status));
        return contributions.findAll(spec,page).map(this::contributionResponse);
    }
    public ContributionResponse contribution(UUID id,QuestionActor actor) { return contribution(id,false,actor); }
    public ContributionResponse contribution(UUID id,boolean includeAnswers,QuestionActor actor) {
        var c=contributions.findById(id).orElseThrow(()->missing("Không tìm thấy contribution"));
        if(!actor.systemAdmin()&&!actor.userId().equals(c.getSubmittedBy()))throw missing("Không tìm thấy contribution");
        if(includeAnswers) systemAuthority(actor);
        return contributionResponse(c,includeAnswers);
    }
    public ContributionResponse reviewContribution(UUID id,PublicReview request,QuestionActor actor) {
        throw new ResponseStatusException(HttpStatus.GONE,"Public không còn verification gate; legacy review chỉ đọc");
    }
    public Page<PublicResponse> publicQuestions(UUID bankId,UUID owner,Question.QuestionType type,Question.DifficultyLevel difficulty,String text,
            PublicQuestion.PublicationStatus publicationStatus,boolean mine,Pageable page,QuestionActor actor) {
        return publicQuestions(bankId,owner,type,difficulty,text,publicationStatus,mine,null,null,null,"NEWEST",page,actor);
    }
    public Page<PublicResponse> publicQuestions(UUID bankId,UUID owner,Question.QuestionType type,Question.DifficultyLevel difficulty,String text,
            PublicQuestion.PublicationStatus publicationStatus,boolean mine,UUID subjectId,UUID categoryId,PublicQuestion.PostType postType,
            String ranking,Pageable page,QuestionActor actor) {
        bounds(page);
        UUID effectiveOwner=owner;
        if(mine) {
            if(owner!=null&&!owner.equals(actor.userId())) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"mine chỉ được truy vấn publication của actor");
            effectiveOwner=actor.userId();
        }
        if(publicationStatus==PublicQuestion.PublicationStatus.ARCHIVED&&!mine)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"ARCHIVED chỉ owner truy vấn qua mine=true");
        if(ranking==null)ranking="NEWEST";
        String ordering=rankingOrder(ranking);
        if(text!=null&&!text.isBlank()) {
            if(text.length()>200) throw bad("Tìm kiếm tối đa 200 ký tự");
            text="%"+text.trim().toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        }
        entityManager.flush();
        String filter=" WHERE (CAST(:anyStatus AS boolean) OR p.status=CAST(:status AS varchar)) AND (CAST(:bank AS uuid) IS NULL OR p.bank_id=CAST(:bank AS uuid)) AND (CAST(:owner AS uuid) IS NULL OR p.source_owner_user_id=CAST(:owner AS uuid))"+
                " AND (CAST(:postType AS varchar) IS NULL OR p.post_type=CAST(:postType AS varchar)) AND (CAST(:text AS varchar) IS NULL OR lower(p.search_content) LIKE CAST(:text AS varchar) ESCAPE E'\\\\')"+
                " AND (CAST(:subject AS uuid) IS NULL OR EXISTS(SELECT 1 FROM public_question_items i WHERE i.publication_id=p.id AND i.subject_id=CAST(:subject AS uuid)))"+
                " AND (CAST(:category AS uuid) IS NULL OR EXISTS(SELECT 1 FROM public_question_items i WHERE i.publication_id=p.id AND i.category_id=CAST(:category AS uuid)))"+
                " AND (CAST(:type AS varchar) IS NULL OR EXISTS(SELECT 1 FROM public_question_items i WHERE i.publication_id=p.id AND i.question_type=CAST(:type AS varchar)))"+
                " AND (CAST(:difficulty AS varchar) IS NULL OR EXISTS(SELECT 1 FROM public_question_items i WHERE i.publication_id=p.id AND i.difficulty_level=CAST(:difficulty AS varchar)))";
        var args=new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("anyStatus",mine&&publicationStatus==null)
                .addValue("status",(publicationStatus==null?PublicQuestion.PublicationStatus.PUBLISHED:publicationStatus).name()).addValue("bank",bankId)
                .addValue("owner",effectiveOwner).addValue("postType",postType==null?null:postType.name()).addValue("text",text)
                .addValue("subject",subjectId).addValue("category",categoryId).addValue("type",type==null?null:type.name())
                .addValue("difficulty",difficulty==null?null:difficulty.name()).addValue("limit",page.getPageSize()).addValue("offset",page.getOffset());
        var ids=jdbc.query("SELECT p.id FROM public_questions p"+filter+ordering+" LIMIT :limit OFFSET :offset",args,(row,n)->row.getObject(1,UUID.class));
        long total=jdbc.queryForObject("SELECT count(*) FROM public_questions p"+filter,args,Long.class);
        var byId=new HashMap<UUID,PublicQuestion>();publications.findAllById(ids).forEach(p->byId.put(p.getId(),p));
        return new PageImpl<>(ids.stream().map(byId::get).filter(Objects::nonNull).map(p->publicResponse(p,actor)).toList(),page,total);
    }
    public PublicResponse publicQuestion(UUID id,QuestionActor actor) {
        var p=publication(id);
        if(p.getStatus()==PublicQuestion.PublicationStatus.ARCHIVED&&!p.getSourceOwnerUserId().equals(actor.userId()))
            throw missing("Không tìm thấy PublicQuestion");
        return publicResponse(p,actor);
    }
    private String rankingOrder(String ranking) {
        if("NEWEST".equals(ranking))return " ORDER BY p.verified_at DESC,p.id ASC";
        String interval;String mode;
        if(ranking.endsWith("_DAY"))interval="24 hours";else if(ranking.endsWith("_MONTH"))interval="30 days";else if(ranking.endsWith("_YEAR"))interval="365 days";else throw conflict("ranking không hợp lệ");
        if(ranking.startsWith("COPY_"))mode="COPY";else if(ranking.startsWith("RATING_"))mode="RATING";else throw conflict("ranking không hợp lệ");
        if(mode.equals("COPY"))return " ORDER BY (SELECT count(DISTINCT e.actor_user_id) FROM public_question_copy_events e WHERE e.publication_id=p.id AND e.actor_user_id<>p.source_owner_user_id AND e.copied_at >= timezone('UTC',CURRENT_TIMESTAMP)-INTERVAL '"+interval+"') DESC,p.verified_at DESC,p.id ASC";
        String window="created_at >= timezone('UTC',CURRENT_TIMESTAMP)-INTERVAL '"+interval+"' AND publication_id=p.id AND actor_user_id<>p.source_owner_user_id";
        String count="(SELECT count(*) FROM public_question_ratings r WHERE "+window+")";
        String sum="(SELECT COALESCE(sum(rating),0) FROM public_question_ratings r WHERE "+window+")";
        return " ORDER BY CASE WHEN "+count+"=0 THEN 0 ELSE ("+sum+"+25.0)/("+count+"+5.0) END DESC,"+count+" DESC,p.verified_at DESC,p.id ASC";
    }
    @Transactional
    public PublicRatingResponse ratePublic(UUID id,PublicRatingRequest request,QuestionActor actor) {
        if(actor.internal()||actor.userId()==null||!Set.of("TEACHER","STUDENT").contains(actor.role()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Đánh giá cần tài khoản Teacher hoặc Student");
        var p=publications.findByIdForUpdate(id).orElseThrow(()->missing("Không tìm thấy Public post"));
        if(p.getStatus()!=PublicQuestion.PublicationStatus.PUBLISHED)throw missing("Không tìm thấy Public post");
        if(p.getSourceOwnerUserId().equals(actor.userId()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Không thể tự đánh giá bài đăng của mình");
        jdbc.update("INSERT INTO public_question_ratings(publication_id,actor_user_id,rating,created_at,updated_at) VALUES(:post,:actor,:rating,timezone('UTC',CURRENT_TIMESTAMP),timezone('UTC',CURRENT_TIMESTAMP)) ON CONFLICT(publication_id,actor_user_id) DO UPDATE SET rating=EXCLUDED.rating,updated_at=timezone('UTC',CURRENT_TIMESTAMP)",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("post",id).addValue("actor",actor.userId()).addValue("rating",request.rating()));
        return ratingResponse(id,actor);
    }
    private PublicRatingResponse ratingResponse(UUID id,QuestionActor actor) {
        var summary=jdbc.queryForMap("SELECT COALESCE(AVG(rating),0)::numeric AS average_rating,COUNT(*) AS rating_count FROM public_question_ratings WHERE publication_id=:id",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("id",id));
        var my=jdbc.query("SELECT rating FROM public_question_ratings WHERE publication_id=:id AND actor_user_id=:actor",new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("id",id).addValue("actor",actor.userId()),(row,n)->row.getBigDecimal(1)).stream().findFirst().orElse(null);
        return new PublicRatingResponse(id,((BigDecimal)summary.get("average_rating")).setScale(2,RoundingMode.HALF_UP),((Number)summary.get("rating_count")).longValue(),my);
    }
    @Transactional
    public PublicViewResponse recordPublicView(UUID id,QuestionActor actor) {
        if(actor.internal()||actor.userId()==null||!Set.of("TEACHER","STUDENT").contains(actor.role()))throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Xem public post cần tài khoản Teacher hoặc Student");
        var p=publications.findByIdForUpdate(id).orElseThrow(()->missing("Không tìm thấy Public post"));
        if(p.getStatus()!=PublicQuestion.PublicationStatus.PUBLISHED)throw missing("Không tìm thấy Public post");
        if(!p.getSourceOwnerUserId().equals(actor.userId()))jdbc.update("INSERT INTO public_question_view_events(publication_id,actor_user_id,event_day,created_at) VALUES(:post,:actor,timezone('UTC',CURRENT_TIMESTAMP)::date,timezone('UTC',CURRENT_TIMESTAMP)) ON CONFLICT(publication_id,actor_user_id,event_day) DO NOTHING",new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("post",id).addValue("actor",actor.userId()));
        return viewResponse(id);
    }
    private PublicViewResponse viewResponse(UUID id){return new PublicViewResponse(id,viewCount(id,24),viewCount(id,30),viewCount(id,365));}
    private long viewCount(UUID id,int days){return jdbc.queryForObject("SELECT count(*) FROM public_question_view_events WHERE publication_id=:id AND event_day>timezone('UTC',CURRENT_TIMESTAMP)::date-:days AND created_at>=timezone('UTC',CURRENT_TIMESTAMP)-(:days * INTERVAL '1 day')",new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("id",id).addValue("days",days),Long.class);}
    @Transactional
    public void archivePublic(UUID id,QuestionActor actor) {
        var p=publications.findByIdForUpdate(id).orElseThrow(()->missing("Không tìm thấy verified PublicQuestion"));
        if(!p.getSourceOwnerUserId().equals(actor.userId())) throw missing("Không tìm thấy PublicQuestion");
        if(p.getStatus()==PublicQuestion.PublicationStatus.ARCHIVED) throw conflict("PublicQuestion đã được archive");
        p.setStatus(PublicQuestion.PublicationStatus.ARCHIVED);p.setArchivedBy(actor.userId());p.setArchivedAt(LocalDateTime.now());
    }
    private PublicQuestion publication(UUID id) {return publications.findById(id).orElseThrow(()->missing("Không tìm thấy verified PublicQuestion"));}
    private Optional<LineageNode> lineageSource(QuestionLineage l,Optional<LineageNode> publication,QuestionActor actor) {
        if(l.getSourceBankType()==BankType.PUBLIC||l.getSourceBankType()==BankType.ORGANIZATION) {
            if(publication.isEmpty()||!publication.get().available()) return Optional.of(unavailable(LineageRelation.SOURCE,l.getSourceQuestionId(),l.getSourceQuestionVersion(),null,BankType.PERSONAL,true));
            try {
                UUID sourceBank;
                String frozen;
                if(l.getSourceBankType()==BankType.PUBLIC) {
                    var p=publications.findById(l.getSourcePublicQuestionId()).orElseThrow();
                    var item=publicItems.findFirstByPublicationIdAndSourceQuestionIdAndSourceQuestionVersion(p.getId(),l.getSourceQuestionId(),l.getSourceQuestionVersion()).orElseThrow();
                    frozen=item.getSnapshot();sourceBank=item.getSourceBankId();
                } else {
                    var p=organizationQuestions.findById(l.getSourceOrganizationQuestionId()).orElseThrow();
                    frozen=p.getSnapshot();sourceBank=p.getSourceBankId();
                }
                var q=read(frozen);
                return Optional.of(new LineageNode(LineageRelation.SOURCE,l.getSourceQuestionId(),l.getSourceQuestionVersion(),sourceBank,
                        BankType.PERSONAL,q.status()==null?null:q.status().name(),readableBankName(sourceBank,actor),true,true));
            } catch(RuntimeException ignored) { return Optional.of(unavailable(LineageRelation.SOURCE,l.getSourceQuestionId(),l.getSourceQuestionVersion(),null,BankType.PERSONAL,true)); }
        }
        try {
            var source=questions.findById(l.getSourceQuestionId()).orElseThrow();policy.requireRead(source,actor);
            var frozen=snapshot(l.getSourceQuestionId(),l.getSourceQuestionVersion());
            return Optional.of(new LineageNode(LineageRelation.SOURCE,l.getSourceQuestionId(),l.getSourceQuestionVersion(),l.getSourceBankId(),
                    l.getSourceBankType(),frozen.status()==null?null:frozen.status().name(),bankName(l.getSourceBankId()),true,true));
        } catch(RuntimeException ignored) { return Optional.of(unavailable(LineageRelation.SOURCE,l.getSourceQuestionId(),l.getSourceQuestionVersion(),l.getSourceBankId(),l.getSourceBankType(),true)); }
    }
    private Optional<LineageNode> lineagePublication(QuestionLineage l,QuestionActor actor) {
        if(l.getSourcePublicQuestionId()!=null) {
            var p=publications.findById(l.getSourcePublicQuestionId());
            if(p.isEmpty()||(p.get().getStatus()==PublicQuestion.PublicationStatus.ARCHIVED&&!p.get().getSourceOwnerUserId().equals(actor.userId())))
                return Optional.of(unavailable(LineageRelation.PUBLICATION,l.getSourcePublicQuestionId(),l.getSourceQuestionVersion(),l.getSourceBankId(),BankType.PUBLIC,true));
            return Optional.of(new LineageNode(LineageRelation.PUBLICATION,p.get().getId(),p.get().getSourceQuestionVersion(),p.get().getBankId(),BankType.PUBLIC,
                    p.get().getStatus().name(),bankName(p.get().getBankId()),true,true));
        }
        if(l.getSourceOrganizationQuestionId()!=null) {
            var p=organizationQuestions.findById(l.getSourceOrganizationQuestionId());
            if(p.isEmpty()) return Optional.of(unavailable(LineageRelation.PUBLICATION,l.getSourceOrganizationQuestionId(),l.getSourceQuestionVersion(),l.getSourceBankId(),BankType.ORGANIZATION,true));
            try { registry.requireRead(registry.get(p.get().getBankId()),actor); }
            catch(RuntimeException denied) { return Optional.of(unavailable(LineageRelation.PUBLICATION,p.get().getId(),p.get().getSourceQuestionVersion(),p.get().getBankId(),BankType.ORGANIZATION,true)); }
            return Optional.of(new LineageNode(LineageRelation.PUBLICATION,p.get().getId(),p.get().getSourceQuestionVersion(),p.get().getBankId(),BankType.ORGANIZATION,
                    "APPROVED",bankName(p.get().getBankId()),true,true));
        }
        return Optional.empty();
    }
    private LineageNode unavailable(LineageRelation relation,UUID id,long version,UUID bankId,BankType type,boolean frozen) {
        return new LineageNode(relation,id,version,bankId,type,null,null,false,frozen);
    }
    private String readableBankName(UUID bankId,QuestionActor actor) {
        try {
            var bank=banks.findById(bankId).orElse(null);
            if(bank==null) return null;
            registry.requireRead(bank,actor);
            return bank.getName();
        } catch(RuntimeException denied) { return null; }
    }
    private String bankName(UUID bankId) {return banks.findById(bankId).map(QuestionBank::getName).orElse(null);}
    private QuestionResponse snapshot(UUID id,long version) {return read(versions.findByQuestionIdAndVersionNumber(id,version).orElseThrow(()->missing("Không tìm thấy version")).getSnapshot());}
    private QuestionResponse read(String value) {try{return json.readValue(value,QuestionResponse.class);}catch(JsonProcessingException e){throw new IllegalStateException("Invalid immutable snapshot",e);}}
    private String write(QuestionResponse value) {try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("Invalid immutable snapshot",e);}}
    private BankResponse bankResponse(QuestionBank b) {return new BankResponse(b.getId(),b.getType(),b.getOwnerUserId(),b.getOrganizationId(),b.getName(),b.getStatus(),b.isDefaultBank(),b.getCreatedAt());}
    private ContributionResponse contributionResponse(PublicContribution c) { return contributionResponse(c,false); }
    private ContributionResponse contributionResponse(PublicContribution c,boolean includeAnswers) {return new ContributionResponse(c.getId(),c.getSourceQuestionId(),c.getSourceQuestionVersion(),c.getSourceBankId(),c.getSourceOwnerUserId(),c.getSubmittedBy(),c.getSubmittedAt(),c.getStatus(),c.getReviewerId(),c.getReviewNote(),c.getReviewedAt(),includeAnswers?read(c.getSnapshot()):read(c.getSnapshot()).withoutAnswers());}
    private PublicResponse publicResponse(PublicQuestion p,QuestionActor actor) {
        var itemEntities=publicItems.findByPublicationIdOrderByPosition(p.getId());
        var itemResponses=itemEntities.stream().map(i->{
            var q=read(i.getSnapshot());return new PublicPostItemResponse(i.getId(),i.getPosition(),i.getSourceQuestionId(),i.getSourceQuestionVersion(),q.withoutAnswers());}).toList();
        var safeQuestion=itemResponses.isEmpty()?read(p.getSnapshot()).withoutAnswers():itemResponses.getFirst().question();
        var publicSubjects=itemEntities.stream().filter(i->i.getSubjectId()!=null).collect(java.util.stream.Collectors.toMap(PublicQuestionItem::getSubjectId,i->new PublicSubject(i.getSubjectId(),i.getSubjectName()),(a,b)->a,LinkedHashMap::new)).values().stream().toList();
        var publicCategories=itemEntities.stream().filter(i->i.getCategoryId()!=null).collect(java.util.stream.Collectors.toMap(PublicQuestionItem::getCategoryId,i->new PublicCategory(i.getCategoryId(),i.getCategoryName(),i.getSubjectId(),i.getSubjectName(),i.getCategoryParentId(),readPath(i.getCategoryPath())),(a,b)->a,LinkedHashMap::new)).values().stream().toList();
        var summary=ratingResponse(p.getId(),actor);
        var views=viewResponse(p.getId());
        return new PublicResponse(p.getId(),p.getBankId(),p.getSourceQuestionId(),p.getSourceQuestionVersion(),p.getSourceBankId(),p.getSourceOwnerUserId(),p.getSourceOwnerDisplayName(),p.getStatus(),p.getVerifiedBy(),p.getVerifiedAt(),p.getArchivedBy(),p.getArchivedAt(),safeQuestion,p.getPostType(),p.getTitle(),p.getDescription(),p.getPostVersion(),p.getItemCount(),itemResponses,publicSubjects,publicCategories,copyCount(p.getId(),24),copyCount(p.getId(),30),copyCount(p.getId(),365),summary.averageRating(),summary.ratingCount(),summary.myRating(),views.views24h(),views.views30d(),views.views365d());
    }
    private List<String> readPath(String value) {try{return value==null?List.of():json.readValue(value,new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){});}catch(Exception e){return List.of();}}
    private String writeCategoryPath(Category category) {try{var names=new ArrayList<String>();for(var c=category;c!=null;c=c.getParent())names.add(c.getName());Collections.reverse(names);return json.writeValueAsString(names);}catch(Exception e){throw new IllegalStateException("Cannot serialize taxonomy snapshot",e);}}
    private long copyCount(UUID publicationId,int days) {
        return jdbc.queryForObject("SELECT count(DISTINCT actor_user_id) FROM public_question_copy_events WHERE publication_id=:id AND actor_user_id<>(SELECT source_owner_user_id FROM public_questions WHERE id=:id) AND copied_at>=timezone('UTC',CURRENT_TIMESTAMP)-(:days * INTERVAL '1 day')",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("id",publicationId).addValue("days",days),Long.class);
    }
    private void systemAuthority(QuestionActor actor) {if(!actor.systemAdmin())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Yêu cầu signed systemPrivilege=ADMIN");}
    private void bounds(Pageable p) {if(p.getPageSize()>100)throw bad("size tối đa 100");}
    private ResponseStatusException bad(String s){return new ResponseStatusException(HttpStatus.BAD_REQUEST,s);}
    private ResponseStatusException conflict(String s){return new ResponseStatusException(HttpStatus.CONFLICT,s);}
    private ResponseStatusException missing(String s){return new ResponseStatusException(HttpStatus.NOT_FOUND,s);}
}

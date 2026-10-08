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
import org.springframework.transaction.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
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
    private final QuestionService core;
    private final QuestionPolicy policy;
    private final OrganizationAuthorizationClient organizationAuthorization;
    private final AuthIdentityClient identities;
    private final ObjectMapper json;

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
        core.findById(id,false,actor);
        var l=lineage.findById(id).orElseThrow(()->missing("Không có lineage cho câu hỏi gốc"));
        return new LineageResponse(l.getQuestionId(),l.getSourceQuestionId(),l.getSourceQuestionVersion(),l.getSourceBankId(),l.getSourceBankType(),l.getSourcePublicQuestionId(),l.getSourceOrganizationQuestionId(),l.getCopiedAt(),l.getCopiedBy());
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
        if(p.getStatus()!=PublicQuestion.PublicationStatus.PUBLISHED) throw conflict("PublicQuestion đã archive");
        if(p.getSourceQuestionVersion()!=request.sourceQuestionVersion()) throw conflict("Public source version không khớp");
        return copy(read(p.getSnapshot()),request.targetBankId(),p.getBankId(),BankType.PUBLIC,p.getId(),actor);
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
                return publicResponse(previous.get());
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
                .questionType(frozen.questionType()).difficultyLevel(frozen.difficultyLevel()).verifiedBy(actor.userId()).verifiedAt(now).build());
        return publicResponse(p);
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
    public Page<PublicResponse> publicQuestions(UUID bankId,UUID owner,Question.QuestionType type,Question.DifficultyLevel difficulty,String text,Pageable page) {
        bounds(page);Specification<PublicQuestion> spec=(r,q,b)->b.equal(r.get("status"),PublicQuestion.PublicationStatus.PUBLISHED);
        if(bankId!=null)spec=spec.and((r,q,b)->b.equal(r.get("bankId"),bankId));
        if(owner!=null)spec=spec.and((r,q,b)->b.equal(r.get("sourceOwnerUserId"),owner));
        if(type!=null)spec=spec.and((r,q,b)->b.equal(r.get("questionType"),type));
        if(difficulty!=null)spec=spec.and((r,q,b)->b.equal(r.get("difficultyLevel"),difficulty));
        if(text!=null&&!text.isBlank()) {
            if(text.length()>200) throw bad("Tìm kiếm tối đa 200 ký tự");
            String term="%"+text.trim().toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
            spec=spec.and((r,q,b)->b.like(b.lower(r.get("searchContent")),term,'\\'));
        }
        return publications.findAll(spec,page).map(this::publicResponse);
    }
    public PublicResponse publicQuestion(UUID id,QuestionActor actor) {
        var p=publication(id);
        if(p.getStatus()==PublicQuestion.PublicationStatus.ARCHIVED&&!p.getSourceOwnerUserId().equals(actor.userId()))
            throw missing("Không tìm thấy PublicQuestion");
        return publicResponse(p);
    }
    @Transactional
    public void archivePublic(UUID id,QuestionActor actor) {
        var p=publication(id);
        if(!p.getSourceOwnerUserId().equals(actor.userId())) throw missing("Không tìm thấy PublicQuestion");
        if(p.getStatus()==PublicQuestion.PublicationStatus.ARCHIVED) throw conflict("PublicQuestion đã được archive");
        p.setStatus(PublicQuestion.PublicationStatus.ARCHIVED);p.setArchivedBy(actor.userId());p.setArchivedAt(LocalDateTime.now());
    }
    private PublicQuestion publication(UUID id) {return publications.findById(id).orElseThrow(()->missing("Không tìm thấy verified PublicQuestion"));}
    private QuestionResponse snapshot(UUID id,long version) {return read(versions.findByQuestionIdAndVersionNumber(id,version).orElseThrow(()->missing("Không tìm thấy version")).getSnapshot());}
    private QuestionResponse read(String value) {try{return json.readValue(value,QuestionResponse.class);}catch(JsonProcessingException e){throw new IllegalStateException("Invalid immutable snapshot",e);}}
    private String write(QuestionResponse value) {try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException("Invalid immutable snapshot",e);}}
    private BankResponse bankResponse(QuestionBank b) {return new BankResponse(b.getId(),b.getType(),b.getOwnerUserId(),b.getOrganizationId(),b.getName(),b.getStatus(),b.isDefaultBank(),b.getCreatedAt());}
    private ContributionResponse contributionResponse(PublicContribution c) { return contributionResponse(c,false); }
    private ContributionResponse contributionResponse(PublicContribution c,boolean includeAnswers) {return new ContributionResponse(c.getId(),c.getSourceQuestionId(),c.getSourceQuestionVersion(),c.getSourceBankId(),c.getSourceOwnerUserId(),c.getSubmittedBy(),c.getSubmittedAt(),c.getStatus(),c.getReviewerId(),c.getReviewNote(),c.getReviewedAt(),includeAnswers?read(c.getSnapshot()):read(c.getSnapshot()).withoutAnswers());}
    private PublicResponse publicResponse(PublicQuestion p) {return new PublicResponse(p.getId(),p.getBankId(),p.getSourceQuestionId(),p.getSourceQuestionVersion(),p.getSourceBankId(),p.getSourceOwnerUserId(),p.getSourceOwnerDisplayName(),p.getStatus(),p.getVerifiedBy(),p.getVerifiedAt(),p.getArchivedBy(),p.getArchivedAt(),read(p.getSnapshot()).withoutAnswers());}
    private void systemAuthority(QuestionActor actor) {if(!actor.systemAdmin())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Yêu cầu signed systemPrivilege=ADMIN");}
    private void bounds(Pageable p) {if(p.getPageSize()>100)throw bad("size tối đa 100");}
    private ResponseStatusException bad(String s){return new ResponseStatusException(HttpStatus.BAD_REQUEST,s);}
    private ResponseStatusException conflict(String s){return new ResponseStatusException(HttpStatus.CONFLICT,s);}
    private ResponseStatusException missing(String s){return new ResponseStatusException(HttpStatus.NOT_FOUND,s);}
}

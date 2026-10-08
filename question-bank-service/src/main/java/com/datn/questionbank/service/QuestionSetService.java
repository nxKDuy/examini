package com.datn.questionbank.service;

import com.datn.questionbank.dto.BankDto.*;
import com.datn.questionbank.dto.QuestionDto.*;
import com.datn.questionbank.entity.*;
import com.datn.questionbank.repository.*;
import com.datn.questionbank.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;

@Service @RequiredArgsConstructor
@Transactional(readOnly=true)
public class QuestionSetService {
    private final QuestionSetRepository sets;
    private final QuestionSetVersionRepository versions;
    private final QuestionRepository questions;
    private final QuestionVersionRepository questionVersions;
    private final CategoryRepository categories;
    private final SubjectRepository subjects;
    private final QuestionBankRepository banks;
    private final QuestionLineageRepository lineage;
    private final QuestionService core;
    private final PublicQuestionRepository publications;
    private final PublicQuestionItemRepository items;
    private final BankRegistry registry;
    private final QuestionPolicy policy;
    private final AuthIdentityClient identities;
    private final ObjectMapper json;
    private final NamedParameterJdbcTemplate jdbc;

    @Transactional public QuestionSetResponse create(UUID bankId,QuestionSetWrite request,QuestionActor actor) {
        policy.requireAuthor(actor);
        var bank=registry.get(bankId);registry.requireWrite(bank,actor);
        if(bank.getType()!=QuestionBank.BankType.PERSONAL)throw bad("Question Set chỉ lưu trong Personal Bank");
        var snapshot=resolve(request.items(),actor);
        var now=LocalDateTime.now();var set=sets.save(QuestionSet.builder().bankId(bankId).ownerUserId(actor.userId())
                .title(request.title().trim()).description(blankNull(request.description())).currentVersion(1)
                .status(QuestionSet.SetStatus.ACTIVE).createdAt(now).updatedAt(now).build());
        saveVersion(set,request.title(),request.description(),snapshot,actor);
        return response(set,snapshot);
    }
    @Transactional public QuestionSetResponse update(UUID id,QuestionSetWrite request,QuestionActor actor) {
        var set=owned(id,actor);if(set.getStatus()!=QuestionSet.SetStatus.ACTIVE)throw conflict("Question Set đã archive");
        if(request.expectedVersion()==null||request.expectedVersion()!=set.getCurrentVersion())throw conflict("Set version đã thay đổi");
        var snapshot=resolve(request.items(),actor);set.setTitle(request.title().trim());set.setDescription(blankNull(request.description()));
        set.setCurrentVersion(set.getCurrentVersion()+1);set.setUpdatedAt(LocalDateTime.now());
        saveVersion(set,request.title(),request.description(),snapshot,actor);return response(set,snapshot);
    }
    public Page<QuestionSetResponse> list(UUID bankId,Pageable page,QuestionActor actor) {
        bounds(page);var bank=registry.get(bankId);registry.requireRead(bank,actor);
        if(!Objects.equals(bank.getOwnerUserId(),actor.userId()))throw missing();
        return sets.findByBankIdAndOwnerUserId(bankId,actor.userId(),page).map(s->response(s,readVersion(s)));
    }
    public QuestionSetResponse get(UUID id,QuestionActor actor) {var s=owned(id,actor);return response(s,readVersion(s));}
    public QuestionSetResponse version(UUID id,long version,QuestionActor actor) {
        var s=owned(id,actor);var v=versions.findById(new QuestionSetVersion.Key(id,version)).orElseThrow(this::missing);
        try{return response(s,json.readValue(v.getSnapshot(),SetSnapshot.class));}catch(Exception e){throw new IllegalStateException("Invalid Question Set snapshot",e);}
    }
    @Transactional public void archive(UUID id,QuestionActor actor) {var s=owned(id,actor);s.setStatus(QuestionSet.SetStatus.ARCHIVED);s.setUpdatedAt(LocalDateTime.now());}

    @Transactional public PublicResponse publish(UUID id,PublishSetRequest versionRequest,QuestionActor actor) {
        var set=owned(id,actor);if(set.getStatus()!=QuestionSet.SetStatus.ACTIVE)throw conflict("Question Set đã archive");
        if(versionRequest.version()!=set.getCurrentVersion())throw conflict("Set version đã thay đổi");
        var existing=publications.findByQuestionSetIdAndQuestionSetVersion(id,set.getCurrentVersion());
        if(existing.isPresent()) {
            if(existing.get().getStatus()==PublicQuestion.PublicationStatus.ARCHIVED)throw conflict("Set version đã unpublish; tạo version mới để publish lại");
            return publicResponse(existing.get());
        }
        var versionSnapshot=readVersion(set);var questionsSnapshot=versionSnapshot.items();if(questionsSnapshot.isEmpty())throw bad("Set cần ít nhất một câu hỏi");
        var first=questionsSnapshot.getFirst();var now=LocalDateTime.now();var idValue=UUID.randomUUID();
        var pub=publications.save(PublicQuestion.builder().id(idValue).bankId(banks.findByTypeAndDefaultBankTrue(QuestionBank.BankType.PUBLIC).orElseThrow().getId())
                .contributionId(null).sourceQuestionId(first.id()).sourceQuestionVersion(first.questionVersion()).sourceBankId(first.bankId())
                .sourceOwnerUserId(actor.userId()).sourceOwnerDisplayName(identities.displayName(actor.userId())).snapshot(write(first))
                .searchContent(String.join(" ",java.util.stream.Stream.concat(java.util.stream.Stream.of(versionSnapshot.title(),versionSnapshot.description()==null?"":versionSnapshot.description()),questionsSnapshot.stream().map(QuestionResponse::content)).toList()))
                .status(PublicQuestion.PublicationStatus.PUBLISHED).questionType(first.questionType()).difficultyLevel(first.difficultyLevel())
                .verifiedBy(actor.userId()).verifiedAt(now).postType(PublicQuestion.PostType.QUESTION_SET).title(versionSnapshot.title())
                .description(versionSnapshot.description()).postVersion(set.getCurrentVersion()).itemCount(questionsSnapshot.size())
                .questionSetId(set.getId()).questionSetVersion(set.getCurrentVersion()).build());
        for(int i=0;i<questionsSnapshot.size();i++) {
            var q=questionsSnapshot.get(i);items.save(PublicQuestionItem.builder().id(UUID.randomUUID()).publicationId(pub.getId()).position(i)
                    .sourceQuestionId(q.id()).sourceQuestionVersion(q.questionVersion()).sourceBankId(q.bankId()).sourceOwnerUserId(actor.userId())
                    .snapshot(write(q)).searchContent(q.content()).questionType(q.questionType()).difficultyLevel(q.difficultyLevel())
                    .categoryId(q.categoryId()).subjectId(questionSubject(q.categoryId())).build());
        }
        return publicResponse(pub);
    }
    @Transactional public QuestionSetResponse fork(UUID publicationId,QuestionSetForkRequest request,QuestionActor actor) {
        policy.requireAuthor(actor);var publication=publications.findById(publicationId).orElseThrow(this::missing);
        if(publication.getStatus()!=PublicQuestion.PublicationStatus.PUBLISHED)throw conflict("Public post đã archive");
        var target=request.targetBankId()==null?registry.defaultPersonal(actor.userId()):request.targetBankId();
        var bank=registry.get(target);registry.requireWrite(bank,actor);
        if(bank.getType()!=QuestionBank.BankType.PERSONAL)throw bad("Fork target phải là Personal Bank");
        var published=items.findByPublicationIdOrderByPosition(publicationId);if(published.isEmpty())throw conflict("Public post không có snapshot item");
        var copied=new ArrayList<QuestionResponse>();for(var item:published){var source=read(item.getSnapshot());
            var fork=core.copySnapshot(source,target,Question.SourceType.PUBLIC_BANK,publicationId.toString(),actor);
            lineage.save(QuestionLineage.builder().questionId(fork.id()).sourceQuestionId(item.getSourceQuestionId())
                    .sourceQuestionVersion(item.getSourceQuestionVersion()).sourceBankId(publication.getBankId())
                    .sourceBankType(QuestionBank.BankType.PUBLIC).sourcePublicQuestionId(publicationId)
                    .copiedAt(LocalDateTime.now()).copiedBy(actor.userId()).build());copied.add(fork);
        }
        var now=LocalDateTime.now();var set=sets.save(QuestionSet.builder().bankId(target).ownerUserId(actor.userId())
                .title(publication.getTitle()==null?"Forked public questions":publication.getTitle())
                .description(publication.getDescription()).currentVersion(1).status(QuestionSet.SetStatus.ACTIVE).createdAt(now).updatedAt(now).build());
        saveVersion(set,set.getTitle(),set.getDescription(),copied,actor);
        if(!publication.getSourceOwnerUserId().equals(actor.userId()))jdbc.update("INSERT INTO public_question_copy_events(publication_id,actor_user_id) VALUES(:post,:actor)",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("post",publicationId).addValue("actor",actor.userId()));
        return response(set,copied);
    }

    private List<QuestionResponse> resolve(List<SetItemRequest> input,QuestionActor actor) {
        var seen=new HashSet<UUID>();var out=new ArrayList<QuestionResponse>();
        for(var request:input){
            if(!seen.add(request.questionId()))throw bad("Set không nhận câu hỏi trùng lặp");
            var q=questions.findById(request.questionId()).orElseThrow(this::missing);policy.requireOwner(q,actor);
            if(q.getScope()!=Question.QuestionScope.PERSONAL||q.getStatus()!=Question.QuestionStatus.ACTIVE||q.getQuestionVersion()!=request.questionVersion())
                throw conflict("Set chỉ nhận Question cá nhân ACTIVE ở version hiện tại");
            var version=questionVersions.findByQuestionIdAndVersionNumber(q.getId(),q.getQuestionVersion()).orElseThrow(this::missing);
            try{out.add(json.readValue(version.getSnapshot(),QuestionResponse.class));}catch(Exception e){throw new IllegalStateException("Invalid question snapshot",e);}
        }
        return List.copyOf(out);
    }
    private UUID questionSubject(UUID categoryId){return categories.findById(categoryId).map(c->c.getSubject().getId()).orElse(null);}
    private void saveVersion(QuestionSet set,String title,String description,List<QuestionResponse> snapshot,QuestionActor actor){
        try{versions.save(new QuestionSetVersion(set.getId(),set.getCurrentVersion(),json.writeValueAsString(new SetSnapshot(title,blankNull(description),snapshot)),actor.userId(),LocalDateTime.now()));}
        catch(Exception e){throw new IllegalStateException("Cannot serialize Question Set version",e);}
    }
    private SetSnapshot readVersion(QuestionSet set){
        var v=versions.findById(new QuestionSetVersion.Key(set.getId(),set.getCurrentVersion())).orElseThrow(this::missing);
        try{return json.readValue(v.getSnapshot(),SetSnapshot.class);}catch(Exception e){throw new IllegalStateException("Invalid Question Set snapshot",e);}
    }
    private record SetSnapshot(String title,String description,List<QuestionResponse> items) {}
    private QuestionSetResponse response(QuestionSet set,SetSnapshot setSnapshot){return response(set,setSnapshot.items());}
    private QuestionSetResponse response(QuestionSet set,List<QuestionResponse> snapshot){
        var safe=new ArrayList<PublicPostItemResponse>();for(int i=0;i<snapshot.size();i++){var q=snapshot.get(i);safe.add(new PublicPostItemResponse(q.id(),i,q.id(),q.questionVersion(),q.withoutAnswers()));}
        return new QuestionSetResponse(set.getId(),set.getBankId(),set.getOwnerUserId(),set.getTitle(),set.getDescription(),set.getCurrentVersion(),set.getStatus(),set.getUpdatedAt(),safe);
    }
    private PublicResponse publicResponse(PublicQuestion p){
        var itemRows=items.findByPublicationIdOrderByPosition(p.getId());
        var listed=itemRows.stream().map(i->{try{var q=json.readValue(i.getSnapshot(),QuestionResponse.class);return new PublicPostItemResponse(i.getId(),i.getPosition(),i.getSourceQuestionId(),i.getSourceQuestionVersion(),q.withoutAnswers());}catch(Exception e){throw new IllegalStateException(e);}}).toList();
        var categoryRows=categories.findAllById(itemRows.stream().map(PublicQuestionItem::getCategoryId).filter(Objects::nonNull).distinct().toList());
        var subjectRows=subjects.findAllById(itemRows.stream().map(PublicQuestionItem::getSubjectId).filter(Objects::nonNull).distinct().toList());
        var subjectNames=new HashMap<UUID,String>();subjectRows.forEach(s->subjectNames.put(s.getId(),s.getName()));
        var publicSubjects=subjectRows.stream().map(s->new PublicSubject(s.getId(),s.getName())).toList();
        var publicCategories=categoryRows.stream().map(c->new PublicCategory(c.getId(),c.getName(),c.getSubject().getId(),subjectNames.get(c.getSubject().getId()),c.getParent()==null?null:c.getParent().getId())).toList();
        var q=read(p.getSnapshot());return new PublicResponse(p.getId(),p.getBankId(),p.getSourceQuestionId(),p.getSourceQuestionVersion(),p.getSourceBankId(),p.getSourceOwnerUserId(),p.getSourceOwnerDisplayName(),p.getStatus(),p.getVerifiedBy(),p.getVerifiedAt(),p.getArchivedBy(),p.getArchivedAt(),q.withoutAnswers(),p.getPostType(),p.getTitle(),p.getDescription(),p.getPostVersion(),p.getItemCount(),listed,publicSubjects,publicCategories,0,0,0);
    }
    private QuestionResponse read(String s){try{return json.readValue(s,QuestionResponse.class);}catch(Exception e){throw new IllegalStateException(e);}}
    private String write(QuestionResponse q){try{return json.writeValueAsString(q);}catch(Exception e){throw new IllegalStateException(e);}}
    private QuestionSet owned(UUID id,QuestionActor actor){var s=sets.findById(id).orElseThrow(this::missing);if(!s.getOwnerUserId().equals(actor.userId()))throw missing();return s;}
    private void bounds(Pageable page){if(page.getPageSize()>100)throw bad("size tối đa 100");}
    private String blankNull(String s){return s==null||s.isBlank()?null:s.trim();}
    private ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy tài nguyên");}
    private ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
    private ResponseStatusException conflict(String m){return new ResponseStatusException(HttpStatus.CONFLICT,m);}
}

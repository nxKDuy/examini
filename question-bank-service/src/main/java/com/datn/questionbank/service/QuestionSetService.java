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
import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private final PublicPostStatistics statistics;
    private final PublicBookmarks bookmarks;

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
        policy.requireAuthor(actor);
        var set=ownedForUpdate(id,actor);if(set.getStatus()!=QuestionSet.SetStatus.ACTIVE)throw conflict("Question Set đã archive");
        if(request.expectedVersion()==null||request.expectedVersion()!=set.getCurrentVersion())throw conflict("Set version đã thay đổi");
        var snapshot=resolve(request.items(),actor);set.setTitle(request.title().trim());set.setDescription(blankNull(request.description()));
        set.setCurrentVersion(set.getCurrentVersion()+1);set.setUpdatedAt(LocalDateTime.now());
        saveVersion(set,request.title(),request.description(),snapshot,actor);return response(set,snapshot);
    }
    public Page<QuestionSetResponse> list(UUID bankId,Pageable page,QuestionActor actor) {
        return list(bankId,null,null,page,actor);
    }
    public Page<QuestionSetResponse> list(UUID bankId,String text,QuestionSet.SetStatus status,Pageable page,QuestionActor actor) {
        bounds(page);var bank=registry.get(bankId);registry.requireRead(bank,actor);
        if(!Objects.equals(bank.getOwnerUserId(),actor.userId()))throw missing();
        org.springframework.data.jpa.domain.Specification<QuestionSet> filter=(r,q,b)->b.and(
                b.equal(r.get("bankId"),bankId),b.equal(r.get("ownerUserId"),actor.userId()));
        if(status!=null)filter=filter.and((r,q,b)->b.equal(r.get("status"),status));
        if(text!=null&&text.length()>200)throw bad("Tìm kiếm tối đa 200 ký tự");
        if(text!=null&&!text.isBlank()) {
            var pattern="%"+text.trim().toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
            filter=filter.and((r,q,b)->b.like(b.lower(r.get("title")),pattern,'\\'));
        }
        var stablePage=PageRequest.of(page.getPageNumber(),page.getPageSize(),page.getSort().and(Sort.by("id")));
        return sets.findAll(filter,stablePage).map(s->response(s,readVersion(s)));
    }
    public QuestionSetResponse get(UUID id,QuestionActor actor) {var s=owned(id,actor);return response(s,readVersion(s));}
    public QuestionSetResponse version(UUID id,long version,QuestionActor actor) {
        var s=owned(id,actor);var v=versions.findById(new QuestionSetVersion.Key(id,version)).orElseThrow(this::missing);
        try{return response(s,json.readValue(v.getSnapshot(),SetSnapshot.class),v.getVersionNumber());}catch(Exception e){throw new IllegalStateException("Invalid Question Set snapshot",e);}
    }
    @Transactional public void archive(UUID id,QuestionActor actor) {policy.requireAuthor(actor);var s=ownedForUpdate(id,actor);s.setStatus(QuestionSet.SetStatus.ARCHIVED);s.setUpdatedAt(LocalDateTime.now());}

    @Transactional public PublicResponse publish(UUID id,PublishSetRequest versionRequest,QuestionActor actor) {
        policy.requireAuthor(actor);
        var set=ownedForUpdate(id,actor);if(set.getStatus()!=QuestionSet.SetStatus.ACTIVE)throw conflict("Question Set đã archive");
        if(versionRequest.version()!=set.getCurrentVersion())throw conflict("Set version đã thay đổi");
        var existing=publications.findByQuestionSetIdAndQuestionSetVersion(id,set.getCurrentVersion());
        if(existing.isPresent()) {
            if(existing.get().getStatus()==PublicQuestion.PublicationStatus.ARCHIVED)throw conflict("Set version đã unpublish; tạo version mới để publish lại");
            return publicResponse(existing.get(),actor);
        }
        var versionSnapshot=readVersion(set);var questionsSnapshot=versionSnapshot.items();if(questionsSnapshot.isEmpty())throw bad("Set cần ít nhất một câu hỏi");
        for(var item:questionsSnapshot)if(item.status()!=Question.QuestionStatus.ACTIVE)
            throw conflict("Set item "+item.id()+" version "+item.questionVersion()+" chưa ACTIVE trong snapshot; activate và cập nhật Set trước khi publish");
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
                    .categoryId(q.categoryId()).subjectId(questionSubject(q.categoryId())).categoryName(categoryName(q.categoryId()))
                    .subjectName(subjectName(q.categoryId())).categoryParentId(categoryParent(q.categoryId())).categoryPath(categoryPath(q.categoryId())).build());
        }
        return publicResponse(pub,actor);
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
        if(!publication.getSourceOwnerUserId().equals(actor.userId()))jdbc.update("INSERT INTO public_question_copy_events(publication_id,actor_user_id,copied_at) VALUES(:post,:actor,timezone('UTC',CURRENT_TIMESTAMP))",
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
            try{out.add(json.readValue(version.getSnapshot(),QuestionResponse.class).withContext(q.getStatus(),q.getUpdatedAt()));}
            catch(Exception e){throw new IllegalStateException("Invalid question snapshot",e);}
        }
        return List.copyOf(out);
    }
    private UUID questionSubject(UUID categoryId){return categories.findById(categoryId).map(c->c.getSubject().getId()).orElse(null);}
    private String categoryName(UUID id){return categories.findById(id).map(Category::getName).orElse(null);}
    private String subjectName(UUID id){return categories.findById(id).map(c->c.getSubject().getName()).orElse(null);}
    private UUID categoryParent(UUID id){return categories.findById(id).map(c->c.getParent()==null?null:c.getParent().getId()).orElse(null);}
    private String categoryPath(UUID id){try{var c=categories.findById(id).orElse(null);var names=new ArrayList<String>();for(;c!=null;c=c.getParent())names.add(c.getName());Collections.reverse(names);return json.writeValueAsString(names);}catch(Exception e){throw new IllegalStateException(e);}}
    private void saveVersion(QuestionSet set,String title,String description,List<QuestionResponse> snapshot,QuestionActor actor){
        try{versions.save(new QuestionSetVersion(set.getId(),set.getCurrentVersion(),json.writeValueAsString(new SetSnapshot(title,blankNull(description),snapshot)),actor.userId(),LocalDateTime.now()));}
        catch(Exception e){throw new IllegalStateException("Cannot serialize Question Set version",e);}
    }
    private SetSnapshot readVersion(QuestionSet set){
        var v=versions.findById(new QuestionSetVersion.Key(set.getId(),set.getCurrentVersion())).orElseThrow(this::missing);
        try{return json.readValue(v.getSnapshot(),SetSnapshot.class);}catch(Exception e){throw new IllegalStateException("Invalid Question Set snapshot",e);}
    }
    private record SetSnapshot(String title,String description,List<QuestionResponse> items) {}
    private QuestionSetResponse response(QuestionSet set,SetSnapshot setSnapshot){return response(set,setSnapshot,set.getCurrentVersion());}
    private QuestionSetResponse response(QuestionSet set,SetSnapshot setSnapshot,long version){
        var safe=new ArrayList<PublicPostItemResponse>();for(int i=0;i<setSnapshot.items().size();i++){var q=setSnapshot.items().get(i);safe.add(new PublicPostItemResponse(q.id(),i,q.id(),q.questionVersion(),q.withoutAnswers()));}
        return new QuestionSetResponse(set.getId(),set.getBankId(),set.getOwnerUserId(),setSnapshot.title(),setSnapshot.description(),version,set.getStatus(),set.getUpdatedAt(),safe);
    }
    private QuestionSetResponse response(QuestionSet set,List<QuestionResponse> snapshot){
        var safe=new ArrayList<PublicPostItemResponse>();for(int i=0;i<snapshot.size();i++){var q=snapshot.get(i);safe.add(new PublicPostItemResponse(q.id(),i,q.id(),q.questionVersion(),q.withoutAnswers()));}
        return new QuestionSetResponse(set.getId(),set.getBankId(),set.getOwnerUserId(),set.getTitle(),set.getDescription(),set.getCurrentVersion(),set.getStatus(),set.getUpdatedAt(),safe);
    }
    private PublicResponse publicResponse(PublicQuestion p,QuestionActor actor){
        var itemRows=items.findByPublicationIdOrderByPosition(p.getId());
        var listed=itemRows.stream().map(i->{try{var q=json.readValue(i.getSnapshot(),QuestionResponse.class);return new PublicPostItemResponse(i.getId(),i.getPosition(),i.getSourceQuestionId(),i.getSourceQuestionVersion(),q.withoutAnswers());}catch(Exception e){throw new IllegalStateException(e);}}).toList();
        var publicSubjects=itemRows.stream().filter(i->i.getSubjectId()!=null).collect(java.util.stream.Collectors.toMap(PublicQuestionItem::getSubjectId,i->new PublicSubject(i.getSubjectId(),i.getSubjectName()),(a,b)->a,LinkedHashMap::new)).values().stream().toList();
        var publicCategories=itemRows.stream().filter(i->i.getCategoryId()!=null).collect(java.util.stream.Collectors.toMap(PublicQuestionItem::getCategoryId,i->new PublicCategory(i.getCategoryId(),i.getCategoryName(),i.getSubjectId(),i.getSubjectName(),i.getCategoryParentId(),readPath(i.getCategoryPath())),(a,b)->a,LinkedHashMap::new)).values().stream().toList();
        var q=read(p.getSnapshot());var summary=ratingResponse(p.getId(),actor);var views=statistics.views(p.getId());var copies=statistics.copies(p.getId());return new PublicResponse(p.getId(),p.getBankId(),p.getSourceQuestionId(),p.getSourceQuestionVersion(),p.getSourceBankId(),p.getSourceOwnerUserId(),p.getSourceOwnerDisplayName(),p.getStatus(),p.getVerifiedBy(),p.getVerifiedAt(),p.getArchivedBy(),p.getArchivedAt(),q.withoutAnswers(),p.getPostType(),p.getTitle(),p.getDescription(),p.getPostVersion(),p.getItemCount(),listed,publicSubjects,publicCategories,copies.day(),copies.month(),copies.year(),copies.total(),summary.averageRating(),summary.ratingCount(),summary.myRating(),views.day(),views.month(),views.year(),views.total(),bookmarks.isSaved(p.getId(),actor));
    }
    private List<String> readPath(String value){try{return value==null?List.of():json.readValue(value,new com.fasterxml.jackson.core.type.TypeReference<List<String>>(){});}catch(Exception e){return List.of();}}
    private PublicRatingResponse ratingResponse(UUID id,QuestionActor actor){var summary=jdbc.queryForMap("SELECT COALESCE(AVG(rating),0)::numeric AS average_rating,COUNT(*) AS rating_count FROM public_question_ratings WHERE publication_id=:id",new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("id",id));var my=actor.userId()==null?null:jdbc.query("SELECT rating FROM public_question_ratings WHERE publication_id=:id AND actor_user_id=:actor",new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("id",id).addValue("actor",actor.userId()),(row,n)->row.getBigDecimal(1)).stream().findFirst().orElse(null);return new PublicRatingResponse(id,((BigDecimal)summary.get("average_rating")).setScale(2,RoundingMode.HALF_UP),((Number)summary.get("rating_count")).longValue(),my);}
    private QuestionResponse read(String s){try{return json.readValue(s,QuestionResponse.class);}catch(Exception e){throw new IllegalStateException(e);}}
    private String write(QuestionResponse q){try{return json.writeValueAsString(q);}catch(Exception e){throw new IllegalStateException(e);}}
    private QuestionSet owned(UUID id,QuestionActor actor){var s=sets.findById(id).orElseThrow(this::missing);if(!s.getOwnerUserId().equals(actor.userId()))throw missing();return s;}
    private QuestionSet ownedForUpdate(UUID id,QuestionActor actor){var s=sets.findByIdForUpdate(id).orElseThrow(this::missing);if(!s.getOwnerUserId().equals(actor.userId()))throw missing();return s;}
    private void bounds(Pageable page){if(page.getPageSize()>100)throw bad("size tối đa 100");}
    private String blankNull(String s){return s==null||s.isBlank()?null:s.trim();}
    private ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy tài nguyên");}
    private ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
    private ResponseStatusException conflict(String m){return new ResponseStatusException(HttpStatus.CONFLICT,m);}
}

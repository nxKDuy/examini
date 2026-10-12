package com.datn.questionbank.service;

import com.datn.questionbank.dto.CopySyncDto.*;
import com.datn.questionbank.dto.QuestionDto.QuestionResponse;
import com.datn.questionbank.entity.*;
import com.datn.questionbank.repository.*;
import com.datn.questionbank.security.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.*;

/** Copy synchronization is opt-in. The cursor advances only for explicitly resolved fields/items. */
@Service @RequiredArgsConstructor
@Transactional(readOnly = true)
public class CopySyncService {
    private static final List<QuestionField> QUESTION_FIELDS=List.of(QuestionField.CATEGORY,QuestionField.CONTENT,
            QuestionField.QUESTION_TYPE,QuestionField.DIFFICULTY,QuestionField.OPTIONS,QuestionField.EXPECTED_ANSWER,QuestionField.EXPLANATION);
    private final QuestionRepository questions;
    private final QuestionVersionRepository questionVersions;
    private final QuestionLineageRepository lineages;
    private final QuestionSetRepository sets;
    private final QuestionSetVersionRepository setVersions;
    private final PublicQuestionRepository publications;
    private final PublicQuestionItemRepository publicItems;
    private final OrganizationQuestionRepository organizationQuestions;
    private final QuestionService core;
    private final QuestionPolicy policy;
    private final BankRegistry banks;
    private final ObjectMapper json;
    private final NamedParameterJdbcTemplate jdbc;

    public QuestionPreview question(UUID id,QuestionActor actor) {
        var q=ownedQuestion(id,actor,false);var lineage=lineages.findById(id).orElseThrow(this::missing);
        var cursor=questionCursor(id,lineage);var source=questionSource(lineage,actor);
        var local=fields(questionSnapshot(id,q.getQuestionVersion()));
        var changes=new ArrayList<FieldChange>();
        if(source!=null)for(var field:QUESTION_FIELDS){var key=field.name();var base=cursor.reference().path(key);var now=source.fields().path(key);var own=local.path(key);
            var changed=!base.equals(now);var edited=!base.equals(own);var secret=secret(field);
            changes.add(new FieldChange(field,changed,edited,changed&&edited&&!now.equals(own),secret,
                    secret?null:base,secret?null:own,secret?null:now));
        }
        return new QuestionPreview(id,q.getQuestionVersion(),cursor.sourceVersion(),cursor.revision(),source==null?null:source.version(),
                source==null?Availability.UNAVAILABLE:Availability.AVAILABLE,q.getStatus(),changes);
    }

    @Transactional public QuestionPreview applyQuestion(UUID id,QuestionApplyRequest request,QuestionActor actor) {
        if(request.expectedLocalVersion()==null||request.expectedReferenceSourceVersion()==null||request.expectedCursorRevision()==null||request.expectedSourceVersion()==null||request.decisions()==null||request.decisions().isEmpty())
            throw bad("Cần expectedLocalVersion, expectedSourceVersion và ít nhất một quyết định");
        var q=ownedQuestion(id,actor,true);policy.requireOwner(q,actor);
        if(q.getQuestionVersion()!=request.expectedLocalVersion())throw conflict("Local Question version đã thay đổi");
        var lineage=lineages.findById(id).orElseThrow(this::missing);var cursor=questionCursor(id,lineage);
        if(cursor.sourceVersion()!=request.expectedReferenceSourceVersion()||cursor.revision()!=request.expectedCursorRevision())throw conflict("Copy sync cursor đã thay đổi");
        var source=questionSource(lineage,actor);if(source==null)throw conflict("Nguồn đã gỡ hoặc mất quyền; chỉ xem lịch sử");
        if(source.version()!=request.expectedSourceVersion())throw conflict("Source version đã thay đổi");
        var local=fields(questionSnapshot(id,q.getQuestionVersion()));var next=cursor.reference().deepCopy();
        for(var change:question(id,actor).changes())if(change.conflict()&&!request.decisions().containsKey(change.field()))
            throw conflict("Cần chọn KEEP_LOCAL hoặc TAKE_SOURCE cho field xung đột "+change.field());
        var chosen=new EnumMap<QuestionField,JsonNode>(QuestionField.class);
        for(var entry:request.decisions().entrySet()) {
            var field=entry.getKey();var choice=entry.getValue();
            if(field==null||choice==null||!QUESTION_FIELDS.contains(field))throw bad("Quyết định field không hợp lệ");
            var key=field.name();var before=cursor.reference().path(key);var after=source.fields().path(key);
            if(before.equals(after))throw conflict("Field "+key+" không có thay đổi nguồn");
            if(choice==Choice.TAKE_SOURCE&&!after.equals(local.path(key)))chosen.put(field,after);
            next.set(key,after.deepCopy());
        }
        // A taken value is saved as a normal DRAFT Question revision; source/publication stays immutable.
        // History records the local version produced by this apply (the new DRAFT when a source value was taken).
        long localVersion=chosen.isEmpty()?request.expectedLocalVersion():core.applyCopySync(id,request.expectedLocalVersion(),chosen,actor).questionVersion();
        upsertQuestionCursor(id,next,source.version());event("QUESTION",id,localVersion,source.version(),request.decisions().toString(),actor);
        return question(id,actor);
    }

    public SetPreview set(UUID id,QuestionActor actor) {
        var set=ownedSet(id,actor,false);var origin=setOrigin(id);
        var cursor=setCursor(id,origin);
        var source=setSource(origin);
        var local=localSetSnapshot(id,set.getCurrentVersion());
        var localOrder=localOrder(local);var referenceOrder=referenceOrder(cursor.reference(),local);
        if(source==null)return new SetPreview(id,set.getCurrentVersion(),cursor.sourceVersion(),cursor.revision(),null,Availability.UNAVAILABLE,
                List.of(),List.of(),false,false,false,referenceOrder,List.of(),localOrder);
        var base=cursor.reference();var latest=source.snapshot();
        var metadata=new ArrayList<FieldChange>();for(var field:List.of(QuestionField.TITLE,QuestionField.DESCRIPTION)){
            var key=field.name().toLowerCase(Locale.ROOT);var b=base.path(key);var l=local.path(key);var n=latest.path(key);
            metadata.add(new FieldChange(field,!b.equals(n),!b.equals(l),!b.equals(n)&&!b.equals(l)&&!n.equals(l),false,b,l,n));
        }
        var baseItems=itemsBySource(base);var localItems=localItems(local);var latestItems=itemsBySource(latest);
        var changes=new ArrayList<SetItemChange>();var ids=new LinkedHashSet<UUID>();ids.addAll(baseItems.keySet());ids.addAll(latestItems.keySet());
        for(var sourceId:ids){var b=baseItems.get(sourceId);var n=latestItems.get(sourceId);var l=localItems.get(sourceId);
            String kind=b==null?"ADDED":n==null?"REMOVED":!Objects.equals(b.path("version").asLong(),n.path("version").asLong())||!b.path("question").equals(n.path("question"))?"UPDATED":null;
            if(kind==null)continue;
            boolean edited=b!=null&&(l==null||!b.path("question").equals(fieldsFromSetItem(l.path("question"))));
            boolean conflict=edited&&n!=null&&!n.path("question").equals(fieldsFromSetItem(l.path("question")));
            changes.add(new SetItemChange(sourceId,l==null?null:uuid(l.path("localId")),kind,
                    pos(b),pos(l),pos(n),b==null?null:b.path("version").asLong(),n==null?null:n.path("version").asLong(),edited,conflict));
        }
        boolean sourceReordered=reordered(order(base),order(latest));boolean localReordered=reordered(order(base),orderFromLocal(local));
        return new SetPreview(id,set.getCurrentVersion(),cursor.sourceVersion(),cursor.revision(),source.version(),Availability.AVAILABLE,
                metadata,changes,sourceReordered,localReordered,sourceReordered&&localReordered&&!order(latest).equals(orderFromLocal(local)),
                referenceOrder,sourceOrder(latest,local),localOrder);
    }

    @Transactional public SetPreview applySet(UUID id,SetApplyRequest request,QuestionActor actor) {
        if(request.expectedLocalVersion()==null||request.expectedReferenceSourceVersion()==null||request.expectedCursorRevision()==null||request.expectedSourceVersion()==null||request.decisions()==null||request.decisions().isEmpty())
            throw bad("Cần expectedLocalVersion, expectedSourceVersion và ít nhất một quyết định");
        policy.requireAuthor(actor);var set=ownedSet(id,actor,true);
        if(set.getStatus()!=QuestionSet.SetStatus.ACTIVE)throw conflict("Question Set đã archive");
        if(set.getCurrentVersion()!=request.expectedLocalVersion())throw conflict("Local Set version đã thay đổi");
        var origin=setOrigin(id);var cursor=setCursor(id,origin);
        if(cursor.sourceVersion()!=request.expectedReferenceSourceVersion()||cursor.revision()!=request.expectedCursorRevision())throw conflict("Copy sync cursor đã thay đổi");
        var source=setSource(origin);
        if(source==null)throw conflict("Nguồn đã gỡ hoặc mất quyền; chỉ xem lịch sử");
        if(source.version()!=request.expectedSourceVersion())throw conflict("Source Set version đã thay đổi");
        var preview=set(id,actor);
        for(var change:preview.metadataChanges())if(change.conflict()&&!request.decisions().containsKey(change.field().name()))
            throw conflict("Cần giải quyết metadata xung đột "+change.field());
        for(var change:preview.itemChanges())if(change.conflict()&&!request.decisions().containsKey("ITEM:"+change.sourceQuestionId()))
            throw conflict("Cần giải quyết item xung đột "+change.sourceQuestionId());
        if(preview.reorderConflict()&&!request.decisions().containsKey("ORDER"))throw conflict("Cần giải quyết thứ tự xung đột");
        var local=localSetSnapshot(id,set.getCurrentVersion());var latest=source.snapshot();var next=cursor.reference().deepCopy();
        var localList=new ArrayList<JsonNode>();local.path("items").forEach(localList::add);
        var sourceItems=itemsBySource(latest);var baseline=itemsBySource(cursor.reference());
        var allowed=new HashSet<String>();for(var c:preview.metadataChanges())if(c.sourceChanged())allowed.add(c.field().name());
        for(var c:preview.itemChanges())allowed.add("ITEM:"+c.sourceQuestionId());
        if(preview.sourceReordered())allowed.add("ORDER");
        boolean changed=false;
        for(var entry:request.decisions().entrySet()){
            var key=entry.getKey();var choice=entry.getValue();if(choice==null||!allowed.contains(key))throw bad("Quyết định Set không hợp lệ: "+key);
            if(key.equals("TITLE")||key.equals("DESCRIPTION")){
                var name=key.toLowerCase(Locale.ROOT);next.set(name,latest.path(name).deepCopy());
                if(choice==Choice.TAKE_SOURCE&&!local.path(name).equals(latest.path(name))) {local.set(name,latest.path(name).deepCopy());changed=true;}
            }else if(key.equals("ORDER")){
                if(choice==Choice.TAKE_SOURCE){localList=reorder(localList,order(latest));changed=true;}
                next.set("order",latest.path("order").deepCopy());
            }else{
                var sourceId=UUID.fromString(key.substring(5));var item=sourceItems.get(sourceId);
                if(choice==Choice.TAKE_SOURCE){
                    localList.removeIf(x->sourceId.toString().equals(x.path("sourceId").asText()));
                    if(item!=null){var published=publicItems.findByPublicationIdOrderByPosition(source.publicationId()).stream()
                                .filter(i->i.getSourceQuestionId().equals(sourceId)).findFirst().orElseThrow(this::missing);
                        var frozen=read(published.getSnapshot());
                        var copied=core.copySnapshot(frozen,set.getBankId(),Question.SourceType.PUBLIC_BANK,source.publicationId().toString(),actor);
                        lineages.save(QuestionLineage.builder().questionId(copied.id()).sourceQuestionId(sourceId)
                                .sourceQuestionVersion(item.path("version").asLong()).sourceBankId(source.bankId())
                                .sourceBankType(QuestionBank.BankType.PUBLIC).sourcePublicQuestionId(source.publicationId())
                                .copiedAt(LocalDateTime.now()).copiedBy(actor.userId()).build());
                        var node=json.createObjectNode().put("sourceId",sourceId.toString()).put("localId",copied.id().toString())
                                .put("version",copied.questionVersion()).put("position",item.path("position").asInt());
                        node.set("question",json.valueToTree(copied));localList.add(node);
                    }
                    changed=true;
                }
                if(item==null)baseline.remove(sourceId);else baseline.put(sourceId,item);
            }
        }
        if(changed){
            if(localList.isEmpty())throw conflict("Question Set phải có ít nhất một item");
            if(request.decisions().get("ORDER")!=Choice.TAKE_SOURCE)localList.sort(Comparator.comparingInt(x->x.path("position").asInt()));
            var array=json.createArrayNode();int position=0;for(var item:localList){((ObjectNode)item).put("position",position++);array.add(item);}
            local.set("items",array);
            set.setTitle(local.path("title").asText());set.setDescription(local.path("description").isNull()?null:local.path("description").asText());
            set.setCurrentVersion(set.getCurrentVersion()+1);set.setUpdatedAt(LocalDateTime.now());
            setVersions.save(new QuestionSetVersion(id,set.getCurrentVersion(),writeSetSnapshot(local),actor.userId(),LocalDateTime.now()));
        }
        var merged=json.createArrayNode();baseline.values().forEach(merged::add);next.set("items",merged);
        upsertSetCursor(id,origin,next,source.version());event("QUESTION_SET",id,set.getCurrentVersion(),source.version(),request.decisions().toString(),actor);
        return set(id,actor);
    }

    private Question ownedQuestion(UUID id,QuestionActor actor,boolean lock){var q=(lock?questions.findForUpdate(id):questions.findById(id)).orElseThrow(this::missing);policy.requireRead(q,actor);return q;}
    private QuestionSet ownedSet(UUID id,QuestionActor actor,boolean lock){var s=(lock?sets.findByIdForUpdate(id):sets.findById(id)).orElseThrow(this::missing);
        if(!s.getOwnerUserId().equals(actor.userId()))throw missing();return s;}
    private QuestionResponse questionSnapshot(UUID id,long version){var v=questionVersions.findByQuestionIdAndVersionNumber(id,version).orElseThrow(this::missing);return read(v.getSnapshot());}
    private QuestionResponse read(String value){try{return json.readValue(value,QuestionResponse.class);}catch(Exception e){throw new IllegalStateException("Invalid Question snapshot",e);}}
    private ObjectNode fields(QuestionResponse q){var out=json.createObjectNode();out.put("CATEGORY",q.categoryId().toString());out.put("CONTENT",q.content());
        out.put("QUESTION_TYPE",q.questionType().name());out.put("DIFFICULTY",q.difficultyLevel().name());var opts=json.createArrayNode();
        for(var o:q.options().stream().sorted(Comparator.comparingInt(o->o.displayOrder())).toList())
            opts.add(json.createObjectNode().put("content",o.content()).put("correct",Boolean.TRUE.equals(o.correct())).put("displayOrder",o.displayOrder()));
        out.set("OPTIONS",opts);out.put("EXPECTED_ANSWER",q.expectedAnswer());out.put("EXPLANATION",q.explanation());
        // Canonical JSON round-trip: persisted cursors read back as IntNode, so Short/Long nodes must not make equal values differ.
        return object(string(out));}
    private boolean secret(QuestionField f){return f==QuestionField.OPTIONS||f==QuestionField.EXPECTED_ANSWER||f==QuestionField.EXPLANATION;}
    private record QuestionSource(long version,ObjectNode fields){}
    private QuestionSource questionSource(QuestionLineage l,QuestionActor actor){
        switch(l.getSourceBankType()){
            case PERSONAL,SYSTEM -> {var q=questions.findById(l.getSourceQuestionId()).orElse(null);
                if(q==null||!q.getBankId().equals(l.getSourceBankId())||q.getStatus()!=Question.QuestionStatus.ACTIVE||!policy.accessible(q,actor))return null;
                return new QuestionSource(q.getQuestionVersion(),fields(questionSnapshot(q.getId(),q.getQuestionVersion())));}
            case PUBLIC -> {var base=publications.findById(l.getSourcePublicQuestionId()).orElse(null);if(base==null)return null;
                if(base.getPostType()==PublicQuestion.PostType.SINGLE_QUESTION){var latest=publications.findFirstBySourceQuestionIdAndSourceOwnerUserIdAndPostTypeOrderBySourceQuestionVersionDesc(
                        l.getSourceQuestionId(),base.getSourceOwnerUserId(),PublicQuestion.PostType.SINGLE_QUESTION).orElse(null);
                    return latest==null||latest.getStatus()!=PublicQuestion.PublicationStatus.PUBLISHED?null:new QuestionSource(latest.getSourceQuestionVersion(),fields(read(latest.getSnapshot())));}
                var latest=publications.findFirstByQuestionSetIdOrderByQuestionSetVersionDesc(base.getQuestionSetId()).orElse(null);
                if(latest==null||latest.getStatus()!=PublicQuestion.PublicationStatus.PUBLISHED)return null;return publicItems.findByPublicationIdOrderByPosition(latest.getId()).stream()
                        .filter(i->i.getSourceQuestionId().equals(l.getSourceQuestionId())).findFirst()
                        .map(i->new QuestionSource(i.getSourceQuestionVersion(),fields(read(i.getSnapshot())))).orElse(null);}
            case ORGANIZATION -> {try{banks.requireUse(l.getSourceBankId(),actor);}catch(ResponseStatusException e){return null;}
                var latest=organizationQuestions.findFirstByBankIdAndSourceQuestionIdOrderBySourceQuestionVersionDesc(l.getSourceBankId(),l.getSourceQuestionId()).orElse(null);
                return latest==null?null:new QuestionSource(latest.getSourceQuestionVersion(),fields(read(latest.getSnapshot())));}
        }
        return null;
    }
    private record Cursor(ObjectNode reference,long sourceVersion,long revision){}
    private Cursor questionCursor(UUID id,QuestionLineage lineage){var rows=jdbc.query("SELECT reference_snapshot,last_source_version,cursor_revision FROM question_copy_sync_cursors WHERE question_id=:id",
            new MapSqlParameterSource("id",id),(r,n)->new Cursor(object(r.getString(1)),r.getLong(2),r.getLong(3)));
        return rows.isEmpty()?new Cursor(fields(questionSnapshot(id,1)),lineage.getSourceQuestionVersion(),0):rows.getFirst();}
    private void upsertQuestionCursor(UUID id,ObjectNode reference,long version){jdbc.update("INSERT INTO question_copy_sync_cursors(question_id,reference_snapshot,last_source_version) VALUES(:id,:data,:version) ON CONFLICT(question_id) DO UPDATE SET reference_snapshot=excluded.reference_snapshot,last_source_version=excluded.last_source_version,updated_at=CURRENT_TIMESTAMP,cursor_revision=question_copy_sync_cursors.cursor_revision+1",
            new MapSqlParameterSource().addValue("id",id).addValue("data",string(reference)).addValue("version",version));}
    private record SetSource(UUID publicationId,UUID bankId,long version,ObjectNode snapshot){}
    private UUID setOrigin(UUID id){var first=setSnapshot(id,1).path("items");if(!first.isArray()||first.isEmpty())throw missing();
        var localId=uuid(first.get(0).path("id"));var l=lineages.findById(localId).orElseThrow(this::missing);
        if(l.getSourceBankType()!=QuestionBank.BankType.PUBLIC||l.getSourcePublicQuestionId()==null)throw missing();
        return l.getSourcePublicQuestionId();}
    private SetSource setSource(UUID origin){var base=publications.findById(origin).orElse(null);
        if(base==null||base.getPostType()!=PublicQuestion.PostType.QUESTION_SET)return null;
        var latest=publications.findFirstByQuestionSetIdOrderByQuestionSetVersionDesc(base.getQuestionSetId()).orElse(null);
        if(latest==null||latest.getStatus()!=PublicQuestion.PublicationStatus.PUBLISHED)return null;return new SetSource(latest.getId(),latest.getBankId(),latest.getQuestionSetVersion(),sourceSetSnapshot(latest));}
    private ObjectNode sourceSetSnapshot(PublicQuestion p){var result=json.createObjectNode().put("title",p.getTitle());result.put("description",p.getDescription());var array=json.createArrayNode();var order=json.createArrayNode();
        for(var item:publicItems.findByPublicationIdOrderByPosition(p.getId())){var id=item.getSourceQuestionId();var node=json.createObjectNode().put("sourceId",id.toString())
                .put("version",item.getSourceQuestionVersion()).put("position",item.getPosition());node.set("question",fields(read(item.getSnapshot())));array.add(node);order.add(id.toString());}
        result.set("items",array);result.set("order",order);return result;}
    private ObjectNode setSnapshot(UUID id,long version){var v=setVersions.findById(new QuestionSetVersion.Key(id,version)).orElseThrow(this::missing);
        return object(v.getSnapshot());}
    private ObjectNode localSetSnapshot(UUID id,long version){var raw=setSnapshot(id,version);var result=json.createObjectNode();
        result.set("title",raw.path("title"));result.set("description",raw.path("description"));var array=json.createArrayNode();
        int position=0;for(var question:raw.path("items")){
            var localId=uuid(question.path("id"));var lineage=lineages.findById(localId).orElse(null);
            var node=json.createObjectNode().put("localId",localId.toString())
                    .put("version",question.path("questionVersion").asLong()).put("position",position++);
            if(lineage!=null)node.put("sourceId",lineage.getSourceQuestionId().toString());
            node.set("question",question);array.add(node);
        }
        result.set("items",array);return result;
    }
    private Cursor setCursor(UUID id,UUID origin){var rows=jdbc.query("SELECT reference_snapshot,last_source_set_version,cursor_revision FROM question_set_copy_sync_cursors WHERE question_set_id=:id",
            new MapSqlParameterSource("id",id),(r,n)->new Cursor(object(r.getString(1)),r.getLong(2),r.getLong(3)));
        if(!rows.isEmpty())return rows.getFirst();var base=publications.findById(origin).orElseThrow(this::missing);
        return new Cursor(sourceSetSnapshot(base),base.getQuestionSetVersion(),0);}
    private void upsertSetCursor(UUID id,UUID origin,ObjectNode reference,long version){jdbc.update("INSERT INTO question_set_copy_sync_cursors(question_set_id,source_publication_id,reference_snapshot,last_source_set_version) VALUES(:id,:origin,:data,:version) ON CONFLICT(question_set_id) DO UPDATE SET reference_snapshot=excluded.reference_snapshot,last_source_set_version=excluded.last_source_set_version,updated_at=CURRENT_TIMESTAMP,cursor_revision=question_set_copy_sync_cursors.cursor_revision+1",
            new MapSqlParameterSource().addValue("id",id).addValue("origin",origin).addValue("data",string(reference)).addValue("version",version));}
    private Map<UUID,JsonNode> itemsBySource(JsonNode snapshot){var map=new LinkedHashMap<UUID,JsonNode>();for(var node:snapshot.path("items"))if(node.hasNonNull("sourceId"))map.put(uuid(node.path("sourceId")),node);return map;}
    private Map<UUID,JsonNode> localItems(JsonNode snapshot){var map=new LinkedHashMap<UUID,JsonNode>();
        for(var node:snapshot.path("items"))if(node.hasNonNull("sourceId"))map.put(uuid(node.path("sourceId")),node);return map;}
    private ObjectNode fieldsFromSetItem(JsonNode question){return question.isMissingNode()?json.createObjectNode():fields(json.convertValue(question,QuestionResponse.class));}
    private boolean reordered(List<UUID> before,List<UUID> after){var common=new HashSet<>(before);common.retainAll(after);
        return !before.stream().filter(common::contains).toList().equals(after.stream().filter(common::contains).toList());}
    private List<UUID> order(JsonNode snapshot){var result=new ArrayList<UUID>();for(var item:snapshot.path("order"))result.add(UUID.fromString(item.asText()));return result;}
    private List<UUID> orderFromLocal(JsonNode local){var result=new ArrayList<UUID>();for(var item:local.path("items"))if(item.hasNonNull("sourceId"))result.add(uuid(item.path("sourceId")));return result;}
    private ArrayList<JsonNode> reorder(List<JsonNode> local,List<UUID> sourceOrder){var index=new HashMap<UUID,Integer>();for(int n=0;n<sourceOrder.size();n++)index.put(sourceOrder.get(n),n);
        var result=new ArrayList<>(local);result.sort(Comparator.comparingInt(node->{return node.hasNonNull("sourceId")?index.getOrDefault(uuid(node.path("sourceId")),Integer.MAX_VALUE):Integer.MAX_VALUE;}));return result;}
    private String writeSetSnapshot(ObjectNode local){var out=json.createObjectNode();out.set("title",local.path("title"));out.set("description",local.path("description"));var array=json.createArrayNode();
        for(var node:local.path("items"))array.add(node.path("question"));out.set("items",array);return string(out);}
    private Map<UUID,UUID> localIdsBySource(JsonNode local){var map=new HashMap<UUID,UUID>();
        for(var item:local.path("items"))if(item.hasNonNull("sourceId"))map.put(uuid(item.path("sourceId")),uuid(item.path("localId")));return map;}
    private List<OrderEntry> localOrder(JsonNode local){var result=new ArrayList<OrderEntry>();
        for(var item:local.path("items"))result.add(new OrderEntry(item.path("position").asInt(),item.hasNonNull("sourceId")?uuid(item.path("sourceId")):null,
                uuid(item.path("localId")),item.path("question").path("content").asText(null)));return result;}
    private List<OrderEntry> sourceOrder(JsonNode latest,JsonNode local){var copies=localIdsBySource(local);var result=new ArrayList<OrderEntry>();
        for(var item:latest.path("items")){var sourceId=uuid(item.path("sourceId"));
            result.add(new OrderEntry(item.path("position").asInt(),sourceId,copies.get(sourceId),item.path("question").path("CONTENT").asText(null)));}
        result.sort(Comparator.comparingInt(OrderEntry::position));return result;}
    /** Order the copy was last synced against; content is null for a reference id whose item was later removed. */
    private List<OrderEntry> referenceOrder(JsonNode reference,JsonNode local){var copies=localIdsBySource(local);var items=itemsBySource(reference);var result=new ArrayList<OrderEntry>();
        int position=0;for(var id:order(reference)){var item=items.get(id);
            result.add(new OrderEntry(position++,id,copies.get(id),item==null?null:item.path("question").path("CONTENT").asText(null)));}
        return result;}
    private int pos(JsonNode node){return node==null?-1:node.path("position").asInt();}
    private UUID uuid(JsonNode node){return UUID.fromString(node.asText());}
    private ObjectNode object(String data){try{return (ObjectNode)json.readTree(data);}catch(Exception e){throw new IllegalStateException("Invalid sync snapshot",e);}}
    private String string(JsonNode node){try{return json.writeValueAsString(node);}catch(Exception e){throw new IllegalStateException(e);}}
    private void event(String type,UUID id,long local,long source,String actions,QuestionActor actor){jdbc.update("INSERT INTO copy_sync_events(resource_kind,resource_id,local_version,source_version,actions,actor_user_id) VALUES(:kind,:id,:local,:source,:actions,:actor)",
            new MapSqlParameterSource().addValue("kind",type).addValue("id",id).addValue("local",local).addValue("source",source).addValue("actions",actions).addValue("actor",actor.userId()));}
    public List<SyncEvent> questionHistory(UUID id,QuestionActor actor){ownedQuestion(id,actor,false);return history("QUESTION",id);}
    public List<SyncEvent> setHistory(UUID id,QuestionActor actor){ownedSet(id,actor,false);return history("QUESTION_SET",id);}
    private List<SyncEvent> history(String kind,UUID id){return jdbc.query("SELECT e.id,e.local_version,e.source_version,e.actions,e.actor_user_id,e.created_at,c.corrected_value,c.reason,c.created_at"
            +" FROM copy_sync_events e LEFT JOIN copy_sync_event_corrections c ON c.event_id=e.id AND c.field='LOCAL_VERSION'"
            +" WHERE e.resource_kind=:kind AND e.resource_id=:id ORDER BY e.created_at DESC,e.id DESC",
            new MapSqlParameterSource().addValue("kind",kind).addValue("id",id),(r,n)->new SyncEvent(r.getObject(1,UUID.class),kind,r.getLong(2),r.getLong(3),r.getString(4),r.getObject(5,UUID.class),r.getTimestamp(6).toLocalDateTime(),
                    r.getObject(7,Long.class),r.getString(8),r.getTimestamp(9)==null?null:r.getTimestamp(9).toLocalDateTime()));}
    private ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"Không tìm thấy bản sao hoặc nguồn");}
    private ResponseStatusException bad(String m){return new ResponseStatusException(HttpStatus.BAD_REQUEST,m);}
    private ResponseStatusException conflict(String m){return new ResponseStatusException(HttpStatus.CONFLICT,m);}
}

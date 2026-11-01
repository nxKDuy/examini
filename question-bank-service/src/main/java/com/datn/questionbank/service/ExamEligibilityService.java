package com.datn.questionbank.service;
import com.datn.questionbank.dto.QuestionDto.*;
import com.datn.questionbank.entity.*;
import com.datn.questionbank.repository.*;
import com.datn.questionbank.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
/** All exam selection lanes share this provider-owned, actor-scoped eligibility policy. */
@Service @RequiredArgsConstructor
public class ExamEligibilityService {
 private final QuestionRepository questions;
 private final QuestionVersionRepository versions;
 private final PublicQuestionRepository publicQuestions;
 private final PublicQuestionItemRepository publicItems;
 private final OrganizationQuestionRepository organizationQuestions;
 private final BankRegistry banks;
 private final QuestionPolicy policy;
 private final ObjectMapper json;
 private final NamedParameterJdbcTemplate jdbc;
 public static List<UUID> ids(List<UUID> ids) {
  if(ids==null||ids.isEmpty()||ids.size()>1000||ids.stream().anyMatch(Objects::isNull)) throw error(HttpStatus.BAD_REQUEST,"Batch cần 1..1000 ID hợp lệ");
  var unique=new ArrayList<>(new LinkedHashSet<>(ids));
  if(unique.size()>200) throw error(HttpStatus.BAD_REQUEST,"Tối đa 200 ID riêng biệt");
  return unique;
 }
 public List<QuestionProjection> batch(List<UUID> input,boolean answers,QuestionActor actor) {
  var ids=ids(input);var qmap=new HashMap<UUID,Question>();questions.findAllById(ids).forEach(q->qmap.put(q.getId(),q));
  var snapshots=new HashMap<UUID,QuestionVersion>();versions.findCurrentSnapshots(ids).forEach(v->snapshots.put(v.getQuestionId(),v));
  var pub=new HashMap<UUID,PublicQuestion>();publicQuestions.findAllById(ids).forEach(p->pub.put(p.getId(),p));
  var itemMap=new HashMap<UUID,PublicQuestionItem>();publicItems.findByIdIn(ids).forEach(i->itemMap.put(i.getId(),i));
  var org=new HashMap<UUID,OrganizationQuestion>();organizationQuestions.findAllById(ids).forEach(p->org.put(p.getId(),p));
  List<QuestionProjection> result=new ArrayList<>();
  for(var id:ids) {
   QuestionResponse frozen;
   if(qmap.containsKey(id)) {
    var q=qmap.get(id);
    if(!policy.accessible(q,actor)) throw error(HttpStatus.NOT_FOUND,"Không tìm thấy eligible question/publication");
    if(q.getStatus()!=Question.QuestionStatus.ACTIVE||q.getScope()==Question.QuestionScope.ORGANIZATION) throw error(HttpStatus.CONFLICT,"Question chưa đủ điều kiện ACTIVE");
    if(answers&&!policy.canReadAnswers(q,actor)) throw error(HttpStatus.FORBIDDEN,"Không có quyền đọc key");
    var v=snapshots.get(id);if(v==null||v.getVersionNumber()!=q.getQuestionVersion())throw error(HttpStatus.CONFLICT,"Thiếu current snapshot");
    frozen=read(v.getSnapshot()).withContext(q.getStatus(),q.getUpdatedAt()).forExam(id,q.getBankId(),QuestionBank.BankType.valueOf(q.getScope().name()),null);
   } else if(pub.containsKey(id)||itemMap.containsKey(id)) {
    var item=itemMap.get(id);var p=pub.get(id);
    if(p==null)p=publicQuestions.findById(item.getPublicationId()).orElseThrow(()->error(HttpStatus.NOT_FOUND,"Không tìm thấy eligible publication"));
    if(p.getStatus()!=PublicQuestion.PublicationStatus.PUBLISHED)throw error(HttpStatus.CONFLICT,"PublicQuestion đã archive");
    banks.requireRead(banks.get(p.getBankId()),actor);publicationKey(answers,actor);
    frozen=read(item==null?p.getSnapshot():item.getSnapshot()).forExam(id,p.getBankId(),QuestionBank.BankType.PUBLIC,"PUBLISHED");
   } else if(org.containsKey(id)) {
    var p=org.get(id);banks.requireUse(p.getBankId(),actor);publicationKey(answers,actor);
    frozen=read(p.getSnapshot()).forExam(id,p.getBankId(),QuestionBank.BankType.ORGANIZATION,"APPROVED");
   } else throw error(HttpStatus.NOT_FOUND,"Không tìm thấy eligible question/publication");
   result.add(answers?frozen:frozen.withoutAnswers());
  }
  return result;
 }
 public List<UUID> random(UUID category,Question.DifficultyLevel difficulty,int limit,Question.QuestionType type,UUID bankId,QuestionActor actor) {
  if(limit<1||limit>200)throw error(HttpStatus.BAD_REQUEST,"limit phải từ 1 đến 200");
  return pick(new EligibleRandomRequest(bankId,category,type,difficulty,limit,null),actor).ids();
 }
 /** Blueprint row lane: random within the row filters, excluding ids already picked; reports the real available count. */
 public EligibleRandomResponse pick(EligibleRandomRequest request,QuestionActor actor) {
  if(request.count()<1||request.count()>200)throw error(HttpStatus.BAD_REQUEST,"count phải từ 1 đến 200");
  var exclude=request.excludeQuestionIds()==null?List.<UUID>of():request.excludeQuestionIds();
  if(exclude.size()>1000||exclude.stream().anyMatch(Objects::isNull))throw error(HttpStatus.BAD_REQUEST,"excludeQuestionIds tối đa 1000 ID hợp lệ");
  var filter=new EligibleFilter(request.bankId(),request.categoryId(),request.questionType(),request.difficulty(),null);
  var parameters=parameters(filter,actor).addValue("exclude",exclude.isEmpty()?null:exclude).addValue("limit",request.count());
  var where=where(filter)+(exclude.isEmpty()?"":" AND id NOT IN (:exclude)");
  long available=jdbc.queryForObject("SELECT COUNT(*) FROM ("+ELIGIBLE+") eligible"+where,parameters,Long.class);
  var ids=jdbc.query("SELECT id FROM ("+ELIGIBLE+") eligible"+where+" ORDER BY RANDOM() LIMIT :limit",parameters,(row,n)->row.getObject("id",UUID.class));
  return new EligibleRandomResponse(ids,request.count(),available);
 }
 /** Exam matrix: eligible counts grouped by category and difficulty, in the same universe as the blueprint picks. */
 public List<MatrixCount> matrixCounts(MatrixCountsRequest request,QuestionActor actor) {
  var categories=new LinkedHashSet<>(request.categoryIds()==null?List.<UUID>of():request.categoryIds());
  if(categories.isEmpty()||categories.size()>50||categories.contains(null))throw error(HttpStatus.BAD_REQUEST,"Cần 1..50 chủ đề");
  var filter=new EligibleFilter(request.bankId(),null,request.questionType(),null,null);
  var parameters=parameters(filter,actor).addValue("categories",categories.stream().map(UUID::toString).toList());
  return jdbc.query("SELECT category,difficulty_level,COUNT(*) n FROM ("+ELIGIBLE+") eligible"+where(filter)
    +" AND category IN (:categories) GROUP BY category,difficulty_level ORDER BY category,difficulty_level",parameters,
   (row,n)->new MatrixCount(UUID.fromString(row.getString("category")),Question.DifficultyLevel.valueOf(row.getString("difficulty_level")),row.getLong("n")));
 }
 /** Exam Builder browsing: ACTIVE/published eligible resources only, Safe DTO (no key). */
 public Page<QuestionProjection> page(EligibleFilter filter,Pageable pageable,QuestionActor actor) {
  if(pageable.getPageSize()>100)throw error(HttpStatus.BAD_REQUEST,"size tối đa 100");
  var parameters=parameters(filter,actor).addValue("limit",pageable.getPageSize()).addValue("offset",pageable.getOffset());
  var where=where(filter);
  long total=jdbc.queryForObject("SELECT COUNT(*) FROM ("+ELIGIBLE+") eligible"+where,parameters,Long.class);
  var ids=jdbc.query("SELECT id FROM ("+ELIGIBLE+") eligible"+where+" ORDER BY sort_at DESC,id LIMIT :limit OFFSET :offset",parameters,(row,n)->row.getObject("id",UUID.class));
  return new PageImpl<>(ids.isEmpty()?List.of():batch(ids,false,actor),pageable,total);
 }
 public static final int MAX_SELECT_ALL=500;
 /** "Select all matching": at most 500 ids in browse order; truncated tells the UI more matched. */
 public EligibleIdsResponse ids(EligibleFilter filter,QuestionActor actor) {
  var parameters=parameters(filter,actor).addValue("limit",MAX_SELECT_ALL);
  var where=where(filter);
  long total=jdbc.queryForObject("SELECT COUNT(*) FROM ("+ELIGIBLE+") eligible"+where,parameters,Long.class);
  var ids=jdbc.query("SELECT id FROM ("+ELIGIBLE+") eligible"+where+" ORDER BY sort_at DESC,id LIMIT :limit",parameters,(row,n)->row.getObject("id",UUID.class));
  return new EligibleIdsResponse(ids,total,total>ids.size());
 }
 // Same universe as batch(): Personal ACTIVE (owner), System ACTIVE, Public PUBLISHED items, Organization approved (only for an explicit, BANK_USE-authorized bankId).
 private static final String ELIGIBLE="""
    SELECT q.id,q.bank_id,q.question_type,q.difficulty_level,q.category_id::text category,q.content search,q.updated_at sort_at
     FROM questions q WHERE q.status='ACTIVE' AND
      ((q.scope='PERSONAL' AND q.created_by=:owner) OR q.scope='SYSTEM')
    UNION ALL
    SELECT i.id,p.bank_id,i.question_type,i.difficulty_level,i.category_id::text,i.search_content,p.verified_at
     FROM public_question_items i JOIN public_questions p ON p.id=i.publication_id WHERE p.status='PUBLISHED'
    UNION ALL
    SELECT p.id,p.bank_id,p.question_type,p.difficulty_level,p.snapshot::jsonb->>'categoryId',p.snapshot::jsonb->>'content',p.approved_at
     FROM organization_questions p WHERE p.bank_id=CAST(:bank AS uuid)
   """;
 private MapSqlParameterSource parameters(EligibleFilter filter,QuestionActor actor) {
  if(filter.text()!=null&&filter.text().length()>200)throw error(HttpStatus.BAD_REQUEST,"Tìm kiếm tối đa 200 ký tự");
  if(filter.bankId()!=null) {var bank=banks.get(filter.bankId());banks.requireRead(bank,actor);if(bank.getType()==QuestionBank.BankType.ORGANIZATION)banks.requireUse(filter.bankId(),actor);}
  var text=filter.text()==null||filter.text().isBlank()?null
   :"%"+filter.text().trim().toLowerCase(Locale.ROOT).replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
  return new MapSqlParameterSource().addValue("owner",actor.userId()).addValue("bank",filter.bankId())
   .addValue("category",filter.categoryId()==null?null:filter.categoryId().toString())
   .addValue("difficulty",filter.difficulty()==null?null:filter.difficulty().name())
   .addValue("type",filter.questionType()==null?null:filter.questionType().name()).addValue("text",text);
 }
 private static String where(EligibleFilter filter) {
  return " WHERE (CAST(:bank AS uuid) IS NULL OR bank_id=CAST(:bank AS uuid))"
   +(filter.categoryId()==null?"":" AND category=:category")
   +(filter.difficulty()==null?"":" AND difficulty_level=:difficulty")
   +(filter.questionType()==null?"":" AND question_type=:type")
   +(filter.text()==null||filter.text().isBlank()?"":" AND lower(search) LIKE :text ESCAPE '\\'");
 }
 private void publicationKey(boolean answers,QuestionActor actor){if(answers&&!actor.internal())throw error(HttpStatus.FORBIDDEN,"Publication key chỉ cấp qua server credential lane");}
 private QuestionResponse read(String s){try{return json.readValue(s,QuestionResponse.class);}catch(Exception e){throw new IllegalStateException("Invalid frozen snapshot",e);}}
 private static ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
}

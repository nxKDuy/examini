package com.datn.questionbank.service;
import com.datn.questionbank.dto.QuestionDto.*;
import com.datn.questionbank.entity.*;
import com.datn.questionbank.repository.*;
import com.datn.questionbank.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
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
   } else if(pub.containsKey(id)) {
    var p=pub.get(id);if(p.getStatus()!=PublicQuestion.PublicationStatus.PUBLISHED)throw error(HttpStatus.CONFLICT,"PublicQuestion đã archive");
    banks.requireRead(banks.get(p.getBankId()),actor);publicationKey(answers,actor);
    frozen=read(p.getSnapshot()).forExam(id,p.getBankId(),QuestionBank.BankType.PUBLIC,"PUBLISHED");
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
  if(bankId!=null) {var bank=banks.get(bankId);banks.requireRead(bank,actor);if(bank.getType()==QuestionBank.BankType.ORGANIZATION)banks.requireUse(bankId,actor);}
  var parameters=new MapSqlParameterSource().addValue("category",category.toString()).addValue("difficulty",difficulty.name())
   .addValue("type",type==null?null:type.name()).addValue("owner",actor.userId()).addValue("bank",bankId).addValue("limit",limit);
  return jdbc.query("""
   SELECT id FROM (
    SELECT q.id,q.bank_id,q.question_type,q.difficulty_level,q.category_id::text category
     FROM questions q WHERE q.status='ACTIVE' AND
      ((q.scope='PERSONAL' AND q.created_by=:owner) OR q.scope='SYSTEM')
    UNION ALL
    SELECT p.id,p.bank_id,p.question_type,p.difficulty_level,p.snapshot::jsonb->>'categoryId'
     FROM public_questions p WHERE p.status='PUBLISHED'
    UNION ALL
    SELECT p.id,p.bank_id,p.question_type,p.difficulty_level,p.snapshot::jsonb->>'categoryId'
     FROM organization_questions p WHERE p.bank_id=CAST(:bank AS uuid)
   ) eligible WHERE category=:category AND difficulty_level=:difficulty
     AND (CAST(:type AS varchar) IS NULL OR question_type=CAST(:type AS varchar))
     AND (CAST(:bank AS uuid) IS NULL OR bank_id=CAST(:bank AS uuid))
   ORDER BY RANDOM() LIMIT :limit
   """,parameters,(row,n)->row.getObject("id",UUID.class));
 }
 private void publicationKey(boolean answers,QuestionActor actor){if(answers&&!actor.internal())throw error(HttpStatus.FORBIDDEN,"Publication key chỉ cấp qua server credential lane");}
 private QuestionResponse read(String s){try{return json.readValue(s,QuestionResponse.class);}catch(Exception e){throw new IllegalStateException("Invalid frozen snapshot",e);}}
 private static ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
}

package com.datn.exam;
import com.datn.exam.client.QuestionBankClient;
import com.datn.exam.dto.Snapshot;
import com.datn.exam.entity.Exam;
import com.datn.exam.repository.*;
import com.datn.exam.service.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.util.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:5432/exam_test","spring.datasource.username=postgres","spring.datasource.password=","eureka.client.enabled=false","spring.cloud.discovery.enabled=false","jwt.secret=unit-test-only-signing-key-32-bytes-minimum","exam.internal-token=test-internal","question-bank.internal-token=test-qb"})
@AutoConfigureMockMvc
class ExamCoreTest {
    @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired ExamCore core;@Autowired ExamRepository exams;@Autowired ExamAuditRepository audits;
    @MockBean QuestionBankClient bank;
    @MockBean com.datn.exam.client.SessionClient sessions;
    UUID owner,manager,proctor,student,qid,oid;
    @BeforeEach void setup(){when(sessions.eligibility(any(),any(),anyString())).thenReturn(new com.datn.exam.client.SessionClient.Eligibility(0,false));owner=UUID.randomUUID();manager=UUID.randomUUID();proctor=UUID.randomUUID();student=UUID.randomUUID();qid=UUID.randomUUID();oid=UUID.randomUUID();when(bank.eligible(anyList(),anyString(),any(),eq(true),eq(true))).thenReturn(List.of(question("APPROVED",1L)));}
    QuestionBankClient.Question question(String status,long version){return new QuestionBankClient.Question(qid,version,status,"MULTIPLE_CHOICE","Original",List.of(new Snapshot.Option(oid,"Yes",0,true),new Snapshot.Option(UUID.randomUUID(),"No",1,false)),null);}
    String token(UUID id,String role){return "Bearer "+Jwts.builder().subject(id.toString()).claim("role",role).claim("type","access").expiration(new Date(System.currentTimeMillis()+60000)).signWith(Keys.hmacShaKeyFor("unit-test-only-signing-key-32-bytes-minimum".getBytes(StandardCharsets.UTF_8))).compact();}
    UUID create()throws Exception {String body="{\"title\":\"Exam\",\"durationMinutes\":10,\"passingScore\":0,\"type\":\"EXAM\",\"questions\":[{\"questionId\":\""+qid+"\",\"weight\":2.5}]}";
        var response=mvc.perform(post("/exams/manual").header("Authorization",token(owner,"TEACHER")).contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();return UUID.fromString(json.readTree(response).path("id").asText());}
    @Test void ownershipPermissionsLifecycleAndSafeSnapshot()throws Exception {
        UUID id=create();UUID second=create();assertNotEquals(id,second);
        core.member(id,owner,manager,Exam.MemberRole.MANAGER,false);core.accept(id,manager,"TEACHER");
        core.member(id,owner,proctor,Exam.MemberRole.PROCTOR,false);core.accept(id,proctor,"TEACHER");
        assertThrows(Exception.class,()->core.member(id,manager,owner,Exam.MemberRole.MANAGER,false));
        assertThrows(Exception.class,()->core.transition(id,proctor,Exam.Status.PUBLISHED));
        assertThrows(Exception.class,()->core.transition(id,student,Exam.Status.PUBLISHED));
        assertThrows(Exception.class,()->core.transition(id,owner,Exam.Status.OPEN));
        mvc.perform(get("/exams").header("Authorization",token(manager,"TEACHER"))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1));
        core.assign(id,manager,student,false);core.transition(id,manager,Exam.Status.PUBLISHED);
        assertThrows(Exception.class,()->core.authorizeStart(id,student));
        core.transition(id,manager,Exam.Status.OPEN);core.authorizeStart(id,student);
        when(bank.eligible(anyList(),anyString(),any(),eq(true),eq(true))).thenReturn(List.of(question("APPROVED",2L)));
        var safe=mvc.perform(get("/exams/"+id+"/internal/safe").header("X-Service-Token","test-internal")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(safe.contains("correct"));assertFalse(safe.contains("expectedAnswer"));assertTrue(safe.contains("Original"));assertTrue(safe.contains("2.5"));assertEquals(1,json.readTree(safe).get(0).path("questionVersion").asInt());
        assertThrows(Exception.class,()->core.policy(id,owner,new ExamCore.Policy(Exam.AccessMode.PUBLIC,100,50,1)));
        core.transition(id,owner,Exam.Status.CLOSED);assertThrows(Exception.class,()->core.authorizeStart(id,student));assertThrows(Exception.class,()->core.transition(id,owner,Exam.Status.OPEN));
        assertTrue(audits.findByExamIdOrderByTimestampAsc(id).stream().anyMatch(a->a.getAction().equals("EXAM_CLOSED")));
        mvc.perform(get("/exams/"+id+"/internal/grading").header("Authorization",token(student,"STUDENT"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/exams/"+id+"/internal/grading").header("Authorization",token(owner,"TEACHER")).header("X-Service-Token","test-internal")).andExpect(status().isUnauthorized());
        mvc.perform(get(java.net.URI.create("/exams/"+id+"/inter%6eal/grading")).header("Authorization",token(student,"STUDENT"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/exams/public").header("Authorization",token(owner,"TEACHER"),token(student,"STUDENT"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/exams/public").header("X-User-Id",student)).andExpect(status().isUnauthorized());
        mvc.perform(put("/exams/"+id+"/open").header("Authorization",token(proctor,"TEACHER"))).andExpect(status().isForbidden());
    }
    @Test void eligibilityPolicyAndParticipantRace()throws Exception {
        UUID id=create();when(bank.eligible(anyList(),anyString(),any(),eq(true),eq(true))).thenReturn(List.of(question("DRAFT",1)));
        assertThrows(Exception.class,()->core.transition(id,owner,Exam.Status.PUBLISHED));assertEquals(Exam.Status.DRAFT,exams.findById(id).orElseThrow().getStatus());
        when(bank.eligible(anyList(),anyString(),any(),eq(true),eq(true))).thenReturn(List.of(question("APPROVED",1)));
        assertThrows(Exception.class,()->core.policy(id,owner,new ExamCore.Policy(Exam.AccessMode.PUBLIC,200,101,1)));
        core.policy(id,owner,new ExamCore.Policy(Exam.AccessMode.PUBLIC,1,1,1));core.transition(id,owner,Exam.Status.PUBLISHED);core.transition(id,owner,Exam.Status.OPEN);
        var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try {List<Future<Boolean>> results=new ArrayList<>();for(int i=0;i<2;i++)results.add(pool.submit(()->{gate.await();try{core.join(id,UUID.randomUUID(),null);return true;}catch(Exception e){return false;}}));gate.countDown();int accepted=0;for(var result:results)if(result.get())accepted++;assertEquals(1,accepted);}finally{pool.shutdownNow();}
    }
    @Test void assignedAndCodePoliciesAndTeacherInvitation()throws Exception {
        UUID id=create();core.transition(id,owner,Exam.Status.PUBLISHED);core.transition(id,owner,Exam.Status.OPEN);
        assertThrows(Exception.class,()->core.join(id,student,null));
        UUID codeExam=create();core.policy(codeExam,owner,new ExamCore.Policy(Exam.AccessMode.JOIN_CODE,10,2,1));core.transition(codeExam,owner,Exam.Status.PUBLISHED);core.transition(codeExam,owner,Exam.Status.OPEN);
        assertThrows(Exception.class,()->core.join(codeExam,student,"wrong"));core.join(codeExam,student,exams.findById(codeExam).orElseThrow().getCode());
        core.member(codeExam,owner,student,Exam.MemberRole.MANAGER,false);assertThrows(Exception.class,()->core.accept(codeExam,student,"STUDENT"));
        mvc.perform(get("/exams/"+codeExam).header("Authorization",token(student,"STUDENT"))).andExpect(status().isForbidden());
    }
}

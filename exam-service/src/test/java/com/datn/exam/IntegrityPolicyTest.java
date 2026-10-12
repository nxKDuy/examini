package com.datn.exam;

import com.datn.exam.client.*;
import com.datn.exam.dto.IntegrityPolicy;
import com.datn.exam.entity.Exam;
import com.datn.exam.repository.*;
import com.datn.exam.service.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MockMvc;
import java.nio.charset.StandardCharsets;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:5432/exam_test","spring.datasource.username=postgres","spring.datasource.password=","eureka.client.enabled=false","spring.cloud.discovery.enabled=false","jwt.secret=unit-test-only-signing-key-32-bytes-minimum","exam.internal-token=test-internal","question-bank.internal-token=test-qb"})
@AutoConfigureMockMvc
class IntegrityPolicyTest {
    @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired ExamRepository exams;
    @Autowired ExamCore core; @Autowired ExamAuditRepository audits;
    @MockBean QuestionBankClient bank; @MockBean SessionClient sessions;
    UUID owner,manager,proctor,student,exam;
    String token(UUID id,String role){return "Bearer "+Jwts.builder().subject(id.toString()).claim("role",role).claim("type","access").expiration(new Date(System.currentTimeMillis()+60000)).signWith(Keys.hmacShaKeyFor("unit-test-only-signing-key-32-bytes-minimum".getBytes(StandardCharsets.UTF_8))).compact();}
    @BeforeEach void setup(){
        owner=UUID.randomUUID();manager=UUID.randomUUID();proctor=UUID.randomUUID();student=UUID.randomUUID();
        exam=exams.saveAndFlush(Exam.builder().code(UUID.randomUUID().toString()).title("Integrity").durationMinutes(10).createdBy(owner).status(Exam.Status.DRAFT).build()).getId();
        core.member(exam,owner,manager,Exam.MemberRole.MANAGER,false);core.accept(exam,manager,"TEACHER");
        core.member(exam,owner,proctor,Exam.MemberRole.PROCTOR,false);core.accept(exam,proctor,"TEACHER");core.assign(exam,owner,student,false);
        when(sessions.eligibility(eq(exam),eq(student),eq("test-internal"))).thenReturn(new SessionClient.Eligibility(0,false));
    }
    @Test void policyCrudPermissionsAndAudit()throws Exception {
        var p=new IntegrityPolicy(true,true,true,true,true,true,IntegrityPolicy.WarningPolicy.WARN_ONLY,7);
        for(UUID actor:List.of(owner,manager))mvc.perform(put("/exams/"+exam+"/integrity/policy").header("Authorization",token(actor,"TEACHER")).contentType("application/json").content(json.writeValueAsString(p))).andExpect(status().isOk()).andExpect(jsonPath("$.monitorTabSwitch").value(true)).andExpect(jsonPath("$.warningEventLimit").value(7));
        assertEquals(p,exams.findById(exam).orElseThrow().getIntegrityPolicy());
        for(UUID actor:List.of(proctor,student,UUID.randomUUID()))mvc.perform(put("/exams/"+exam+"/integrity/policy").header("Authorization",token(actor,actor.equals(student)?"STUDENT":"TEACHER")).contentType("application/json").content(json.writeValueAsString(p))).andExpect(status().isForbidden());
        for(UUID actor:List.of(owner,manager,proctor,student))mvc.perform(get("/exams/"+exam+"/integrity/policy").header("Authorization",token(actor,actor.equals(student)?"STUDENT":"TEACHER"))).andExpect(status().isOk());
        mvc.perform(get("/exams/"+exam+"/integrity/policy").header("Authorization",token(UUID.randomUUID(),"TEACHER"))).andExpect(status().isForbidden());
        mvc.perform(get("/exams/"+exam+"/integrity/policy")).andExpect(status().isUnauthorized());
        mvc.perform(put("/exams/"+exam+"/integrity/policy").header("Authorization",token(owner,"TEACHER")).contentType("application/json").content("{}")).andExpect(status().isBadRequest());
        for(int invalid:List.of(0,101))mvc.perform(put("/exams/"+exam+"/integrity/policy").header("Authorization",token(owner,"TEACHER")).contentType("application/json").content(json.writeValueAsString(new IntegrityPolicy(true,true,true,true,true,true,IntegrityPolicy.WarningPolicy.WARN_ONLY,invalid)))).andExpect(status().isBadRequest());
        assertEquals(2,audits.findByExamIdOrderByTimestampAsc(exam).stream().filter(a->a.getAction().equals("INTEGRITY_POLICY_UPDATED")).count());
    }
    @Test void ownerManagerProctorControlWholeExamLockAndStartAuthority()throws Exception {
        when(sessions.integrityLock(eq(exam),any(),any(),eq("test-internal"))).thenAnswer(call->new SessionClient.IntegrityLockResult(exam,true,false,2,java.time.Instant.now()));
        when(sessions.integrityUnlock(eq(exam),any(),eq("test-internal"))).thenAnswer(call->new SessionClient.IntegrityLockResult(exam,false,false,0,java.time.Instant.now()));
        mvc.perform(put("/exams/"+exam+"/integrity/lock").header("Authorization",token(proctor,"TEACHER")).contentType("application/json").content("{\"reason\":\"Manual hold\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.locked").value(true)).andExpect(jsonPath("$.activeSessionsSubmitted").value(2));
        var e=exams.findById(exam).orElseThrow();assertTrue(e.isIntegrityLocked());e.setStatus(Exam.Status.OPEN);exams.saveAndFlush(e);
        assertEquals(409,assertThrows(org.springframework.web.server.ResponseStatusException.class,()->core.authorizeStart(exam,student)).getStatusCode().value());
        mvc.perform(put("/exams/"+exam+"/integrity/unlock").header("Authorization",token(manager,"TEACHER"))).andExpect(status().isOk()).andExpect(jsonPath("$.locked").value(false));
        assertNotNull(core.authorizeStart(exam,student));
        mvc.perform(put("/exams/"+exam+"/integrity/lock").header("Authorization",token(UUID.randomUUID(),"TEACHER"))).andExpect(status().isForbidden());
        assertTrue(audits.findByExamIdOrderByTimestampAsc(exam).stream().anyMatch(a->"EXAM_INTEGRITY_LOCKED".equals(a.getAction())&&a.getMetadata().contains("activeSessionsSubmitted=2")));
        assertTrue(audits.findByExamIdOrderByTimestampAsc(exam).stream().anyMatch(a->"EXAM_INTEGRITY_UNLOCKED".equals(a.getAction())));
    }
    @Test void publishedPolicyFrozenAndExistingDefaultDisabled()throws Exception {
        assertEquals(IntegrityPolicy.disabled(),exams.findById(exam).orElseThrow().getIntegrityPolicy());
        // A pre-existing publication is sufficient to verify its immutable policy gate.
        var e=exams.findById(exam).orElseThrow();e.setStatus(Exam.Status.PUBLISHED);exams.saveAndFlush(e);
        mvc.perform(put("/exams/"+exam+"/integrity/policy").header("Authorization",token(manager,"TEACHER")).contentType("application/json").content(json.writeValueAsString(IntegrityPolicy.disabled()))).andExpect(status().isConflict());
    }
}

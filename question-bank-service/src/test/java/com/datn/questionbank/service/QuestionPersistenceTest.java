package com.datn.questionbank.service;

import com.datn.questionbank.dto.QuestionDto.*;
import com.datn.questionbank.entity.*;
import com.datn.questionbank.repository.*;
import com.datn.questionbank.security.QuestionActor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.data.domain.PageRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static com.datn.questionbank.entity.Question.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"eureka.client.enabled=false", "spring.cloud.discovery.enabled=false",
        "jwt.secret=test-only-jwt-key-32-bytes-or-longer-never-production",
        "question-bank.internal-token=isolated-test-service-token"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="QB_TEST_DATABASE", matches="true")
@Transactional
class QuestionPersistenceTest {
    @Autowired QuestionService service;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    String jwt(QuestionActor actor) {
        return "Bearer " + Jwts.builder().subject(actor.userId().toString()).claim("type","access").claim("role",actor.role())
                .expiration(new Date(System.currentTimeMillis()+60000)).signWith(Keys.hmacShaKeyFor(
                        "test-only-jwt-key-32-bytes-or-longer-never-production".getBytes(StandardCharsets.UTF_8))).compact();
    }
    @Autowired CategoryRepository categories;
    @Autowired QuestionVersionRepository versions;
    @Autowired QuestionRepository questions;
    @Autowired QuestionReviewRepository reviews;
    QuestionActor owner = new QuestionActor(UUID.randomUUID(), "TEACHER", false);
    QuestionActor admin = new QuestionActor(UUID.randomUUID(), "ADMIN", false);
    UUID category;
    @BeforeEach void setup() { category = categories.findAll().getFirst().getId(); }
    QuestionResponse create(String content, DifficultyLevel difficulty) {
        return service.create(new CreateRequest(category, content, QuestionType.MULTIPLE_CHOICE, difficulty, "Book",
                List.of(new OptionRequest("Yes", true, (short)0), new OptionRequest("No", false, (short)1)), null,
                QuestionScope.PERSONAL, SourceType.USER_DOCUMENT, "doc:1", "Book", null, "Excerpt"), owner);
    }
    @Test void migrationBackfillsLegacyDraftAndFullSnapshots() {
        var legacy = questions.findAll().stream().filter(q -> q.getCreatedBy().toString().equals("00000000-0000-0000-0000-000000000001")).toList();
        assertFalse(legacy.isEmpty());
        for (var q : legacy) {
            assertEquals(QuestionStatus.DRAFT, q.getStatus());
            assertTrue(versions.findByQuestionIdAndVersionNumber(q.getId(), 1).isPresent());
            var legacyOwner=new QuestionActor(q.getCreatedBy(),"TEACHER",false);
            assertFalse(((QuestionResponse)service.version(q.getId(), 1, true, legacyOwner).question()).options().isEmpty());
        }
    }
    @Test void actualQuerySelectsOnlyActivePersonalQuestionsForOwner() {
        var approved = create("Active question", DifficultyLevel.EASY);
        service.activate(approved.id(), 1, owner);
        var draft = create("Draft question", DifficultyLevel.EASY);
        var archived = create("Archived question", DifficultyLevel.EASY); service.archive(archived.id(), 1, owner);
        create("Hard question", DifficultyLevel.HARD);
        questions.flush();
        assertEquals(List.of(approved.id()), service.getRandomQuestionIds(category, DifficultyLevel.EASY, 10, null, owner));
        assertTrue(service.getRandomQuestionIds(category, DifficultyLevel.EASY, 10, null, new QuestionActor(UUID.randomUUID(), "TEACHER", false)).isEmpty());
        assertEquals(1, service.findAll(category, DifficultyLevel.EASY, QuestionType.MULTIPLE_CHOICE, QuestionStatus.ACTIVE,
                CreationMethod.MANUAL, SourceType.USER_DOCUMENT, "Active", PageRequest.of(0,20), owner).getTotalElements());
        for (UUID id : List.of(draft.id(), archived.id()))
            assertThrows(ResponseStatusException.class, () -> service.getByIds(List.of(approved.id(), id), true, true, owner));
    }
    @Test void fullSnapshotsAndReviewRemainStableAfterApprovedEditAndArchive() {
        var q = create("Original text", DifficultyLevel.EASY);
        service.activate(q.id(), 1, owner);
        var original = ((QuestionResponse)service.version(q.id(), 1, true, owner).question());
        var next = service.update(q.id(), new UpdateRequest(null,"New text",null,null,null,
                List.of(new OptionRequest("New yes",false,(short)0),new OptionRequest("New no",true,(short)1)),
                null,null,null,null,null,null,1L), owner);
        assertEquals(QuestionStatus.DRAFT, next.status()); assertEquals(2, next.questionVersion());
        assertEquals(original, ((QuestionResponse)service.version(q.id(), 1, true, owner).question()));
        assertNotEquals(original.options().getFirst().id(), next.options().getFirst().id());
        assertTrue(service.reviewHistory(q.id(), owner).isEmpty());
        service.archive(q.id(), 2, owner); assertEquals(2, service.history(q.id(), true, owner).size());
    }
    @Test void csvPersistsCreationAndSourceAndInitialHistory() {
        String csv="content,type,difficulty,expectedAnswer,sourceType,sourceRefId,sourceLabel\nImported text,SHORT_ANSWER,EASY,answer,USER_DOCUMENT,chunk:2,Notes\nBroken text,SHORT_ANSWER,EASY,,NONE,,\n";
        var result=service.importCsv(new MockMultipartFile("file",csv.getBytes()),category,owner);
        assertEquals(1,result.imported()); assertEquals(1,result.failed());
        var q=service.findAll(category,null,null,null,CreationMethod.IMPORT,null,null,PageRequest.of(0,20),owner).getContent().getFirst();
        assertEquals(QuestionStatus.DRAFT,q.status()); assertEquals("chunk:2",q.sourceRefId());
        assertEquals("answer",((QuestionResponse)service.version(q.id(),1,true,owner).question()).expectedAnswer());
    }
    @Test void batchReturnsRequestedOrderAndStableVersions() {
        var a=create("First",DifficultyLevel.EASY); var b=create("Second",DifficultyLevel.EASY);
        for(var q:List.of(a,b)) service.activate(q.id(),1,owner);
        var result=service.getByIds(List.of(b.id(),a.id()),true,false,owner);
        assertEquals(List.of(b.id(),a.id()),result.stream().map(QuestionProjection::id).toList());
        assertTrue(result.stream().allMatch(q->q.questionVersion()==1&&q instanceof SafeQuestionResponse));
    }
    @Test void directHttpWorkflowAndHistoryProjectionsRespectAuthorization() throws Exception {
        http.perform(get("/categories/"+category).header("Authorization",jwt(owner))).andExpect(status().isOk());
        var input=new CreateRequest(category,"HTTP question",QuestionType.MULTIPLE_CHOICE,DifficultyLevel.EASY,"Book",
                List.of(new OptionRequest("Yes",true,(short)0),new OptionRequest("No",false,(short)1)),null,
                QuestionScope.PERSONAL,SourceType.NONE,null,null,null,null);
        var response=http.perform(post("/questions").header("Authorization",jwt(owner)).contentType("application/json")
                .content(json.writeValueAsString(input))).andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        UUID id=UUID.fromString(json.readTree(response).get("id").asText());
        http.perform(put("/questions/"+id).header("Authorization",jwt(owner)).contentType("application/json")
                .content(json.writeValueAsString(new UpdateRequest(null,"Edited HTTP question",null,null,null,null,null,null,null,null,null,null,1L))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.questionVersion").value(2));
        http.perform(post("/questions/"+id+"/activate").header("Authorization",jwt(owner)).contentType("application/json")
                .content("{\"questionVersion\":2}")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
        http.perform(post("/questions/"+id+"/submit-review").header("Authorization",jwt(owner)).contentType("application/json")
                .content("{\"questionVersion\":2}")).andExpect(status().isGone());
        http.perform(post("/questions/"+id+"/review").header("Authorization",jwt(admin)).contentType("application/json")
                .content("{\"questionVersion\":2,\"decision\":\"APPROVED\"}")).andExpect(status().isGone());
        var student=new QuestionActor(owner.userId(),"STUDENT",false);
        for(String path:List.of("/questions/"+id,"/questions/"+id+"/versions","/questions/"+id+"/versions/1","/questions")) {
            String safe=http.perform(get(path).header("Authorization",jwt(student))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertFalse(safe.contains("\"correct\""));assertFalse(safe.contains("expectedAnswer"));assertFalse(safe.contains("sourceExcerpt"));
        }
        for(String path:List.of("/questions/"+id,"/questions/"+id+"/versions","/questions/"+id+"/versions/1"))
            http.perform(get(path).param("includeAnswers","true").header("Authorization",jwt(student))).andExpect(status().isForbidden());
        http.perform(get("/questions/"+id).param("includeAnswers","true").header("Authorization",jwt(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.options[0].correct").value(true));
        http.perform(get("/questions/"+id).header("X-User-Id",owner.userId()).header("X-User-Role","ADMIN"))
                .andExpect(status().isUnauthorized());
    }
    @Test void internalHttpSelectionAndBatchFailClosedAndFreezeVersion() throws Exception {
        var q=create("Internal eligible",DifficultyLevel.EASY);service.activate(q.id(),1,owner);
        String ids=json.writeValueAsString(List.of(q.id(),q.id()));
        var safe=http.perform(post("/questions/batch").header("X-Service-Token","isolated-test-service-token")
                .header("X-Question-Owner-Id",owner.userId()).contentType("application/json").content(ids))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].questionVersion").value(1))
                .andExpect(jsonPath("$[0].options[0].correct").doesNotExist()).andReturn().getResponse().getContentAsString();
        assertFalse(safe.contains("expectedAnswer"));
        http.perform(post("/questions/batch").param("includeAnswers","true").header("X-Service-Token","isolated-test-service-token")
                .header("X-Question-Owner-Id",owner.userId()).contentType("application/json").content(ids))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].options[0].correct").value(true));
        http.perform(post("/questions/batch").param("eligible","false").header("X-Service-Token","isolated-test-service-token")
                .header("X-Question-Owner-Id",owner.userId()).contentType("application/json").content(ids)).andExpect(status().isForbidden());
        String foreign=http.perform(post("/questions/batch").header("X-Service-Token","isolated-test-service-token")
                .header("X-Question-Owner-Id",UUID.randomUUID()).contentType("application/json").content(ids))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        String missing=http.perform(post("/questions/batch").header("X-Service-Token","isolated-test-service-token")
                .header("X-Question-Owner-Id",owner.userId()).contentType("application/json").content(json.writeValueAsString(List.of(UUID.randomUUID()))))
                .andExpect(status().isNotFound()).andReturn().getResponse().getContentAsString();
        assertEquals(json.readTree(foreign).get("detail"),json.readTree(missing).get("detail"));
        http.perform(get("/questions/random").param("categoryId",category.toString()).param("difficulty","EASY").param("limit","200")
                .header("X-Service-Token","isolated-test-service-token").header("X-Question-Owner-Id",owner.userId()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        http.perform(get("/questions/random").param("categoryId",category.toString()).param("difficulty","EASY").param("status","DRAFT")
                .header("Authorization",jwt(owner))).andExpect(status().isBadRequest());
        service.update(q.id(),new UpdateRequest(null,"Edited after publication input",null,null,null,null,null,null,null,null,null,null,1L),owner);
        http.perform(post("/questions/batch").header("X-Service-Token","isolated-test-service-token")
                .header("X-Question-Owner-Id",owner.userId()).contentType("application/json").content(ids)).andExpect(status().isConflict());
        assertEquals("Internal eligible",service.version(q.id(),1,true,owner).question().content());
    }
    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentEditsDoNotOverwriteReviewedRevision() throws Exception {
        var q=create("Concurrent",DifficultyLevel.EASY);
        var gate=new java.util.concurrent.CountDownLatch(1);
        try(var pool=java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var tasks=new ArrayList<java.util.concurrent.Future<Boolean>>();
            for(String content:List.of("Edit A","Edit B")) tasks.add(pool.submit(()-> {
                gate.await();
                try { service.update(q.id(),new UpdateRequest(null,content,null,null,null,null,null,null,null,null,null,null,1L),owner);return true; }
                catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return false;}
            }));
            gate.countDown();int successes=0;for(var task:tasks)if(task.get(10,java.util.concurrent.TimeUnit.SECONDS))successes++;
            assertEquals(1,successes);assertEquals(2,service.history(q.id(),true,owner).size());
        }
    }

}

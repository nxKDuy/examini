package com.datn.questionbank.service;

import com.datn.questionbank.dto.ImportDto.*;
import com.datn.questionbank.dto.QuestionDto.*;
import com.datn.questionbank.entity.Question.*;
import com.datn.questionbank.entity.QuestionImportItem.SourceMethod;
import com.datn.questionbank.entity.QuestionImportJob;
import com.datn.questionbank.entity.QuestionImportJob.Status;
import com.datn.questionbank.repository.*;
import com.datn.questionbank.security.QuestionActor;
import com.datn.questionbank.service.ai.AiQuestionGenerator;
import com.datn.questionbank.service.ai.AiQuestionGenerator.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"eureka.client.enabled=false","spring.cloud.discovery.enabled=false",
        "jwt.secret=test-only-jwt-key-32-bytes-or-longer-never-production",
        "question-bank.internal-token=isolated-test-service-token"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="QB_TEST_DATABASE",matches="true")
@Transactional
class AiGenerationPersistenceTest {
    @Autowired QuestionImportService imports;
    @Autowired QuestionService questions;
    @Autowired QuestionRepository questionRepo;
    @Autowired QuestionImportJobRepository jobRepo;
    @Autowired CategoryRepository categories;
    @Autowired BankRegistry banks;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired MockMvc http;
    @MockBean AuthIdentityClient identities;
    @MockBean AiQuestionGenerator ai;
    QuestionActor owner=new QuestionActor(UUID.randomUUID(),"TEACHER",false);
    QuestionActor other=new QuestionActor(UUID.randomUUID(),"TEACHER",false);
    UUID category;
    @BeforeEach void setup(){
        category=categories.findAll().getFirst().getId();
        when(ai.available()).thenReturn(true);when(ai.model()).thenReturn("gemini/test-model");
    }
    int fails(Runnable call){return assertThrows(ResponseStatusException.class,call::run).getStatusCode().value();}
    static Candidate mc(String content){return new Candidate("MULTIPLE_CHOICE",content,
            List.of(new CandidateOption("Huế",false),new CandidateOption("Hà Nội",true),new CandidateOption("Đà Nẵng",false),new CandidateOption("Cần Thơ",false)),
            null,"Hà Nội là thủ đô từ 1010.","EASY","{\"content\":\""+content+"\"}");}
    AiGenerateRequest request(String material,int count){return new AiGenerateRequest("Địa lý Việt Nam",material,category,count,
            List.of(QuestionType.MULTIPLE_CHOICE,QuestionType.SHORT_ANSWER),DifficultyLevel.MEDIUM,"vi",null,false);}
    /** Generates synchronously (the HTTP path runs the same method after commit). */
    ImportJobResponse run(AiGenerateRequest req,QuestionActor actor,List<String> existing){
        var job=imports.generate(req,actor);
        assertEquals(Status.PROCESSING,job.status());assertEquals(QuestionImportJob.FileKind.AI,job.fileKind());
        imports.runGeneration(job.id(),new Prompt(req.topic(),req.material(),List.of(),req.count(),req.questionTypes(),req.difficulty(),req.language()),existing,actor.userId());
        return imports.get(job.id(),actor);
    }

    @Test void generatedItemsAreReviewedThenImportedAsAiDrafts(){
        // An existing own question in the category becomes AI context and a duplicate reference.
        questions.importDraft(new CreateRequest(category,"Thủ đô của Việt Nam là gì?",QuestionType.SHORT_ANSWER,DifficultyLevel.EASY,null,List.of(),
                "Hà Nội",QuestionScope.PERSONAL,SourceType.NONE,null,null,null,null,null),banks.defaultPersonal(owner.userId()),owner);
        when(ai.generate(any())).thenReturn(new Result(List.of(mc("Thành phố nào là thủ đô của Việt Nam?"),
                new Candidate("SHORT_ANSWER","Sông dài nhất chảy qua Việt Nam?",List.of(),"Mê Kông","Địa lý.","MEDIUM",null),
                mc("THỦ ĐÔ CỦA VIỆT NAM LÀ GÌ")),List.of()));
        var req=request("Hà Nội là thủ đô. Sông Mê Kông dài nhất.",3);
        var job=imports.generate(req,owner);
        var prompt=ArgumentCaptor.forClass(Prompt.class);
        var existing=jdbc.queryForList("SELECT content FROM questions WHERE created_by=:o",Map.of("o",owner.userId()),String.class);
        imports.runGeneration(job.id(),new Prompt(req.topic(),req.material(),existing,3,req.questionTypes(),DifficultyLevel.MEDIUM,"vi"),existing,owner.userId());
        verify(ai).generate(prompt.capture());
        assertEquals(List.of("Thủ đô của Việt Nam là gì?"),prompt.getValue().examples());

        var ready=imports.get(job.id(),owner);
        assertEquals(Status.READY,ready.status());
        assertEquals("AI · Địa lý Việt Nam",ready.fileName());
        assertEquals("gemini/test-model",ready.generation().model());
        assertTrue(ready.generation().hasMaterial());assertEquals(1,ready.generation().exampleCount());
        assertEquals(3,ready.summary().total());assertEquals(3,ready.summary().needsReview());assertEquals(0,ready.summary().importable());

        var items=imports.items(job.id(),ItemFilter.ALL,PageRequest.of(0,50),owner).getContent();
        assertTrue(items.stream().allMatch(i->i.reviewRequired()&&i.valid()&&i.flags().contains("AI_GENERATED")&&i.source().method()==SourceMethod.AI));
        assertEquals(category,items.getFirst().categoryId());
        assertTrue(items.get(2).flags().contains("POSSIBLE_DUPLICATE"));

        // Nothing is imported before a teacher reviews it.
        assertEquals(List.of("NEEDS_REVIEW","NEEDS_REVIEW","NEEDS_REVIEW"),imports.importItems(job.id(),null,owner).skipped().stream().map(SkippedItem::reason).toList());
        var first=items.get(0);var second=items.get(1);
        imports.update(job.id(),first.id(),new ItemUpdate(first.version(),null,null,null,null,null,null,null,true,null),owner);
        imports.update(job.id(),second.id(),new ItemUpdate(second.version(),null,null,DifficultyLevel.HARD,null,null,null,null,true,null),owner);
        imports.update(job.id(),items.get(2).id(),new ItemUpdate(items.get(2).version(),null,null,null,null,null,null,null,null,true),owner);
        var result=imports.importItems(job.id(),null,owner);
        assertEquals(2,result.imported().size());assertEquals(List.of("EXCLUDED"),result.skipped().stream().map(SkippedItem::reason).toList());

        var q=questionRepo.findById(result.imported().getFirst().questionId()).orElseThrow();
        assertEquals(CreationMethod.AI_GENERATED,q.getCreationMethod());
        assertEquals(QuestionStatus.DRAFT,q.getStatus());
        assertEquals(SourceType.USER_DOCUMENT,q.getSourceType());
        assertEquals("ai:"+job.id()+":"+first.id(),q.getSourceRefId());
        assertEquals("AI · Địa lý Việt Nam · gemini/test-model",q.getSourceLabel());
        assertEquals("Hà Nội là thủ đô từ 1010.",q.getExplanation());
        assertEquals(DifficultyLevel.HARD,questionRepo.findById(result.imported().get(1).questionId()).orElseThrow().getDifficultyLevel());
        // The first, fully skipped import attempt is audited too.
        assertEquals(List.of("AI_REQUESTED","AI_GENERATED","IMPORTED","ITEM_UPDATED","ITEM_UPDATED","ITEM_UPDATED","IMPORTED"),
                jdbc.queryForList("SELECT action FROM question_import_events WHERE job_id=:j ORDER BY id",Map.of("j",job.id()),String.class));
    }

    @Test void withoutMaterialTheQuestionHasNoDocumentSource(){
        when(ai.generate(any())).thenReturn(new Result(List.of(mc("Câu hỏi không tài liệu?")),List.of()));
        var job=run(request(null,1),owner,List.of());
        assertFalse(job.generation().hasMaterial());
        var item=imports.items(job.id(),ItemFilter.ALL,PageRequest.of(0,5),owner).getContent().getFirst();
        imports.update(job.id(),item.id(),new ItemUpdate(item.version(),null,null,null,null,null,null,null,true,null),owner);
        var id=imports.importItems(job.id(),null,owner).imported().getFirst().questionId();
        assertEquals(SourceType.NONE,questionRepo.findById(id).orElseThrow().getSourceType());
    }

    @Test void providerFailuresFailTheJobWithAReadableReason(){
        when(ai.generate(any())).thenThrow(new GenerationException("Dịch vụ AI không phản hồi; vui lòng thử lại sau"));
        var failed=run(request(null,2),owner,List.of());
        assertEquals(Status.FAILED,failed.status());assertEquals("Dịch vụ AI không phản hồi; vui lòng thử lại sau",failed.failureReason());
        reset(ai);when(ai.available()).thenReturn(true);when(ai.model()).thenReturn("gemini/test-model");
        when(ai.generate(any())).thenThrow(new IllegalStateException("socket details must not leak"));
        assertEquals("Không tạo được câu hỏi bằng AI",run(request(null,2),owner,List.of()).failureReason());
        reset(ai);when(ai.available()).thenReturn(true);when(ai.model()).thenReturn("gemini/test-model");
        when(ai.generate(any())).thenReturn(new Result(List.of(new Candidate("ESSAY"," ",null,null,null,null,null)),List.of()));
        assertTrue(run(request(null,2),owner,List.of()).failureReason().startsWith("AI không tạo được câu hỏi hợp lệ"));
        when(ai.generate(any())).thenReturn(new Result(List.of(mc("Một câu?")),List.of("Kết quả AI bị cắt do quá dài; một số câu có thể bị thiếu")));
        var partial=run(request(null,2),owner,List.of());
        assertEquals(Status.READY,partial.status());
        assertEquals(List.of("Kết quả AI bị cắt do quá dài; một số câu có thể bị thiếu","AI chỉ tạo được 1/2 câu"),partial.warnings());
    }

    @Test void accessLimitsAndAvailability(){
        assertEquals(403,fails(()->imports.generate(request(null,1),new QuestionActor(UUID.randomUUID(),"STUDENT",false))));
        assertEquals(400,fails(()->imports.generate(new AiGenerateRequest("Chủ đề",null,UUID.randomUUID(),1,null,null,null,null,null),owner)));
        assertEquals(400,fails(()->imports.generate(new AiGenerateRequest("  ",null,category,1,null,null,null,null,null),owner)));
        var job=imports.generate(request(null,1),owner);
        assertEquals(429,fails(()->imports.generate(request(null,1),owner)),"one AI job in flight per teacher");
        assertEquals(404,fails(()->imports.get(job.id(),other)));
        imports.generate(request(null,1),other);
        when(ai.available()).thenReturn(false);
        assertEquals(503,fails(()->imports.generate(request(null,1),other)));
        assertFalse(imports.aiStatus(owner).available());assertNull(imports.aiStatus(owner).model());
        assertEquals(DifficultyLevel.MEDIUM,imports.get(job.id(),owner).defaultDifficulty());
    }

    String jwt(QuestionActor actor){
        return "Bearer "+io.jsonwebtoken.Jwts.builder().subject(actor.userId().toString()).claim("type","access").claim("role",actor.role())
                .expiration(new Date(System.currentTimeMillis()+60000)).signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "test-only-jwt-key-32-bytes-or-longer-never-production".getBytes(java.nio.charset.StandardCharsets.UTF_8))).compact();
    }
    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void httpGeneratesAsynchronouslyAfterCommit() throws Exception {
        when(ai.generate(any())).thenReturn(new Result(List.of(mc("Qua HTTP?")),List.of()));
        http.perform(get("/question-imports/ai/status").header("Authorization",jwt(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true)).andExpect(jsonPath("$.maxQuestions").value(20));
        String tooMany="{\"topic\":\"x\",\"categoryId\":\""+category+"\",\"count\":21}";
        http.perform(post("/question-imports/ai").contentType("application/json").content(tooMany).header("Authorization",jwt(owner))).andExpect(status().isBadRequest());
        String badLang="{\"topic\":\"x\",\"categoryId\":\""+category+"\",\"language\":\"fr\"}";
        http.perform(post("/question-imports/ai").contentType("application/json").content(badLang).header("Authorization",jwt(owner))).andExpect(status().isBadRequest());
        String body="{\"topic\":\"HTTP\",\"categoryId\":\""+category+"\",\"count\":1}";
        var created=http.perform(post("/question-imports/ai").contentType("application/json").content(body).header("Authorization",jwt(owner)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.fileKind").value("AI")).andExpect(jsonPath("$.generation.requestedCount").value(1))
                .andExpect(jsonPath("$.generation.questionTypes[0]").value("MULTIPLE_CHOICE")).andReturn().getResponse().getContentAsString();
        String id=new com.fasterxml.jackson.databind.ObjectMapper().readTree(created).get("id").asText();
        String state="PROCESSING";
        for(int i=0;i<100&&state.equals("PROCESSING");i++){Thread.sleep(100);
            state=new com.fasterxml.jackson.databind.ObjectMapper().readTree(http.perform(get("/question-imports/"+id).header("Authorization",jwt(owner)))
                    .andReturn().getResponse().getContentAsString()).get("status").asText();}
        assertEquals("READY",state);
        http.perform(get("/question-imports/"+id+"/items").param("filter","NEEDS_REVIEW").header("Authorization",jwt(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].source.method").value("AI"));
    }
}

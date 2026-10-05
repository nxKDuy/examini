package com.datn.questionbank.service;

import com.datn.questionbank.dto.BankDto.*;
import com.datn.questionbank.dto.QuestionDto.*;
import com.datn.questionbank.entity.*;
import com.datn.questionbank.repository.*;
import com.datn.questionbank.security.QuestionActor;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import static com.datn.questionbank.entity.Question.*;
import static com.datn.questionbank.entity.QuestionBank.BankType;
import static com.datn.questionbank.entity.PublicContribution.VerificationStatus;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"eureka.client.enabled=false","spring.cloud.discovery.enabled=false",
        "jwt.secret=test-only-jwt-key-32-bytes-or-longer-never-production","question-bank.internal-token=isolated-test-service-token"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="QB_TEST_DATABASE",matches="true")
class MultiBankPersistenceTest {
    @Autowired MultiBankService bankService;
    @Autowired QuestionService core;
    @Autowired QuestionRepository questions;
    @Autowired CategoryRepository categories;
    @Autowired PublicQuestionRepository publicQuestions;
    @Autowired QuestionBankRepository banks;
    @Autowired QuestionVersionRepository versions;
    @Autowired MockMvc http;
    @Autowired ObjectMapper json;
    @Autowired Environment env;
    QuestionActor owner=new QuestionActor(UUID.randomUUID(),"TEACHER",false);
    QuestionActor other=new QuestionActor(UUID.randomUUID(),"TEACHER",false);
    QuestionActor admin=new QuestionActor(UUID.randomUUID(),"ADMIN",false,"ADMIN");
    QuestionActor legacyAdmin=new QuestionActor(UUID.randomUUID(),"ADMIN",false);
    QuestionActor student=new QuestionActor(UUID.randomUUID(),"STUDENT",false);
    UUID category;
    @BeforeEach void setup(){category=categories.findAll().getFirst().getId();}
    CreateRequest input(String content,QuestionScope scope){return new CreateRequest(category,content,QuestionType.MULTIPLE_CHOICE,DifficultyLevel.EASY,"Book",
            List.of(new OptionRequest("Yes",true,(short)0),new OptionRequest("No",false,(short)1)),null,scope,SourceType.USER_DOCUMENT,"doc:1","Book",null,"Private excerpt");}
    QuestionResponse approved(String title){var q=core.create(input(title,QuestionScope.PERSONAL),owner);core.submit(q.id(),1,owner);return core.review(q.id(),new ReviewRequest(QuestionReview.Decision.APPROVED,"OK",1L),admin);}
    UpdateRequest edit(String content,long version){return new UpdateRequest(null,content,null,null,null,null,null,null,null,null,null,null,version);}
    UUID bankOf(UUID q){return questions.findById(q).orElseThrow().getBankId();}
    String jwt(QuestionActor actor){return "Bearer "+Jwts.builder().subject(actor.userId().toString()).claim("type","access").claim("role",actor.role())
            .claim("systemPrivilege",actor.systemPrivilege()).expiration(new Date(System.currentTimeMillis()+60000))
            .signWith(Keys.hmacShaKeyFor("test-only-jwt-key-32-bytes-or-longer-never-production".getBytes(StandardCharsets.UTF_8))).compact();}
    void failure(int status,org.junit.jupiter.api.function.Executable action){assertEquals(status,assertThrows(ResponseStatusException.class,action).getStatusCode().value());}

    @Test void personalBackwardCompatibilityAndNamedBankFilters(){
        var q=approved("Personal legacy route");UUID defaultBank=bankOf(q.id());
        var b=bankService.bank(defaultBank,owner);assertTrue(b.defaultBank());assertEquals(owner.userId(),b.ownerUserId());
        var named=bankService.createBank(new CreateBank(BankType.PERSONAL,"Named",null),owner);
        var inNamed=bankService.createQuestion(named.id(),input("Named question",QuestionScope.PERSONAL),owner);
        assertNotEquals(defaultBank,bankOf(inNamed.question().id()));
        var listing=bankService.listBanks(BankType.PERSONAL,owner.userId(),null,PageRequest.of(0,1),owner);
        assertEquals(2,listing.getTotalElements());assertEquals(1,listing.getContent().size());
        assertEquals(0,bankService.listBanks(BankType.PERSONAL,owner.userId(),null,PageRequest.of(0,20),other).getTotalElements());
        assertEquals(1,bankService.listQuestions(named.id(),QuestionType.MULTIPLE_CHOICE,DifficultyLevel.EASY,QuestionStatus.DRAFT,PageRequest.of(0,20),owner).getTotalElements());
        assertEquals(0,bankService.listQuestions(named.id(),null,DifficultyLevel.HARD,null,PageRequest.of(0,20),owner).getTotalElements());
        failure(403,()->bankService.createQuestion(named.id(),input("Foreign",QuestionScope.PERSONAL),other));
        failure(400,()->bankService.listBanks(null,null,null,PageRequest.of(0,101),owner));
        assertEquals(List.of(q.id()),core.getRandomQuestionIds(category,DifficultyLevel.EASY,200,null,owner));
    }
    @Test void organizationReferenceNeverCreatesMembershipAuthority(){
        UUID organization=UUID.randomUUID();failure(403,()->bankService.createBank(new CreateBank(BankType.ORGANIZATION,"Org",organization),owner));
        var b=bankService.createBank(new CreateBank(BankType.ORGANIZATION,"Org reference",organization),admin);
        assertEquals(organization,b.organizationId());assertNull(b.ownerUserId());assertEquals(QuestionBank.BankStatus.PENDING_AUTHORIZATION,b.status());
        assertEquals(1,bankService.listBanks(BankType.ORGANIZATION,null,organization,PageRequest.of(0,20),admin).getTotalElements());
        failure(404,()->bankService.bank(b.id(),owner));
        failure(503,()->bankService.createQuestion(b.id(),input("Org question",QuestionScope.ORGANIZATION),admin));
        failure(503,()->bankService.listQuestions(b.id(),null,null,null,PageRequest.of(0,20),admin));
        failure(400,()->bankService.createBank(new CreateBank(BankType.ORGANIZATION,"Missing reference",null),admin));
    }
    @Test void systemBankRequiresExplicitPrivilegeAndForkIsIndependent(){
        failure(403,()->bankService.createBank(new CreateBank(BankType.SYSTEM,"System",null),owner));
        failure(403,()->bankService.createBank(new CreateBank(BankType.SYSTEM,"Legacy admin",null),legacyAdmin));
        var b=bankService.createBank(new CreateBank(BankType.SYSTEM,"System",null),admin);
        var q=bankService.createQuestion(b.id(),input("System original",QuestionScope.SYSTEM),admin).question();
        failure(404,()->core.findById(q.id(),false,owner));
        failure(403,()->core.update(q.id(),edit("Hijack",1),owner));
        core.submit(q.id(),1,admin);
        failure(403,()->core.review(q.id(),new ReviewRequest(QuestionReview.Decision.APPROVED,"No",1L),legacyAdmin));
        core.review(q.id(),new ReviewRequest(QuestionReview.Decision.APPROVED,"OK",1L),admin);
        assertEquals(1,bankService.listQuestions(b.id(),null,null,QuestionStatus.APPROVED,PageRequest.of(0,20),owner).getTotalElements());
        assertFalse(json.valueToTree(core.findById(q.id(),false,student)).toString().contains("correct"));
        failure(403,()->core.findById(q.id(),true,owner));
        failure(409,()->core.getByIds(List.of(q.id()),true,false,admin));
        assertFalse(core.getRandomQuestionIds(category,DifficultyLevel.EASY,200,null,admin).contains(q.id()));
        var fork=bankService.forkQuestion(q.id(),new ForkRequest(1,null),owner).question();
        assertEquals(QuestionStatus.DRAFT,fork.status());assertEquals(CreationMethod.COPIED,fork.creationMethod());assertEquals(1,fork.questionVersion());
        assertEquals(BankType.SYSTEM,bankService.lineage(fork.id(),owner).sourceBankType());
        var original=(QuestionResponse)core.findById(q.id(),true,admin);var copied=(QuestionResponse)fork;
        assertNotEquals(original.options().getFirst().id(),copied.options().getFirst().id());
        core.update(q.id(),edit("Changed system",1),admin);
        assertEquals("System original",core.findById(fork.id(),false,owner).content());
        failure(404,()->bankService.forkQuestion(q.id(),new ForkRequest(1,null),owner));
    }
    @Test void approvedPersonalIsNotPublicVerifiedAndRejectionIsTerminal(){
        var draft=core.create(input("Draft contribution",QuestionScope.PERSONAL),owner);
        failure(409,()->bankService.contribute(draft.id(),new ContributionRequest(1),owner));
        var q=approved("Rejected publication");var c=bankService.contribute(q.id(),new ContributionRequest(1),owner);
        assertEquals(VerificationStatus.PUBLIC_PENDING,c.status());
        failure(404,()->core.getByIds(List.of(c.id()),true,false,owner));
        assertEquals(0,bankService.publicQuestions(null,owner.userId(),null,null,PageRequest.of(0,20)).getTotalElements());
        failure(403,()->bankService.reviewContribution(c.id(),new PublicReview(QuestionReview.Decision.APPROVED,"Teacher"),owner));
        failure(403,()->bankService.reviewContribution(c.id(),new PublicReview(QuestionReview.Decision.APPROVED,"Legacy"),legacyAdmin));
        failure(400,()->bankService.reviewContribution(c.id(),new PublicReview(QuestionReview.Decision.REJECTED," "),admin));
        var rejected=bankService.reviewContribution(c.id(),new PublicReview(QuestionReview.Decision.REJECTED,"Needs independent public verification"),admin);
        assertEquals(VerificationStatus.PUBLIC_REJECTED,rejected.status());assertEquals(admin.userId(),rejected.reviewerId());assertNotNull(rejected.reviewedAt());
        failure(409,()->bankService.reviewContribution(c.id(),new PublicReview(QuestionReview.Decision.APPROVED,"Again"),admin));
        failure(409,()->bankService.contribute(q.id(),new ContributionRequest(1),owner));
        assertEquals(0,bankService.publicQuestions(null,owner.userId(),null,null,PageRequest.of(0,20)).getTotalElements());
    }
    @Test void publicSnapshotAndForkSurviveSourceEditArchiveAndKeepLineage(){
        var q=approved("Frozen public v1");var c=bankService.contribute(q.id(),new ContributionRequest(1),owner);
        core.update(q.id(),edit("New private v2",1),owner);core.archive(q.id(),2,owner);
        bankService.reviewContribution(c.id(),new PublicReview(QuestionReview.Decision.APPROVED,"Verified captured v1"),admin);
        var p=bankService.publicQuestions(null,owner.userId(),QuestionType.MULTIPLE_CHOICE,DifficultyLevel.EASY,PageRequest.of(0,20)).getContent().getFirst();
        assertEquals("Frozen public v1",p.question().content());assertEquals(1,p.sourceQuestionVersion());assertEquals(q.id(),p.sourceQuestionId());
        assertNotEquals(q.id(),p.id());assertEquals(QuestionScope.PERSONAL,questions.findById(q.id()).orElseThrow().getScope());
        assertEquals(owner.userId(),questions.findById(q.id()).orElseThrow().getCreatedBy());
        String safe=json.valueToTree(p).toString();assertFalse(safe.contains("correct"));assertFalse(safe.contains("expectedAnswer"));assertFalse(safe.contains("sourceExcerpt"));
        var fork=bankService.forkPublic(p.id(),new ForkRequest(1,null),other).question();
        assertEquals(other.userId(),((QuestionResponse)fork).createdBy());assertEquals(QuestionStatus.DRAFT,fork.status());
        var l=bankService.lineage(fork.id(),other);assertEquals(p.id(),l.sourcePublicQuestionId());assertEquals(q.id(),l.sourceQuestionId());assertEquals(1,l.sourceQuestionVersion());assertEquals(BankType.PUBLIC,l.sourceBankType());
        core.update(fork.id(),edit("Independent fork",1),other);
        assertEquals("Frozen public v1",bankService.publicQuestion(p.id()).question().content());
        assertEquals("New private v2",core.findById(q.id(),false,owner).content());
        failure(404,()->bankService.lineage(fork.id(),owner));
        failure(403,()->bankService.forkPublic(p.id(),new ForkRequest(1,null),student));
        failure(409,()->bankService.forkPublic(p.id(),new ForkRequest(2,null),other));
        failure(409,()->core.getByIds(List.of(fork.id()),true,false,other));
        failure(404,()->core.getByIds(List.of(p.id()),true,false,other));
    }
    @Test void personalHistoricalCopyKeepsVersionAndRejectsForeignTarget(){
        var q=approved("Historical v1");core.update(q.id(),edit("Current v2",1),owner);
        var copy=bankService.forkQuestion(q.id(),new ForkRequest(1,null),owner).question();
        assertEquals("Historical v1",copy.content());assertEquals(1,bankService.lineage(copy.id(),owner).sourceQuestionVersion());
        assertEquals(SourceType.USER_DOCUMENT,copy.sourceType());assertEquals("doc:1",copy.sourceRefId());
        failure(404,()->bankService.forkQuestion(q.id(),new ForkRequest(1,null),other));
        var target=bankService.createBank(new CreateBank(BankType.PERSONAL,"Other bank",null),other);
        failure(403,()->bankService.forkQuestion(q.id(),new ForkRequest(1,target.id()),owner));
        failure(404,()->bankService.forkQuestion(q.id(),new ForkRequest(100,null),owner));
    }
    @Test void publicPaginationFiltersAndContributionVisibility(){
        for(int i=0;i<2;i++){var q=approved("Public listing "+i);var c=bankService.contribute(q.id(),new ContributionRequest(1),owner);bankService.reviewContribution(c.id(),new PublicReview(QuestionReview.Decision.APPROVED,"Verified"),admin);}
        var pending=bankService.contribute(approved("Pending listing").id(),new ContributionRequest(1),owner);
        var page=bankService.publicQuestions(null,owner.userId(),QuestionType.MULTIPLE_CHOICE,DifficultyLevel.EASY,PageRequest.of(0,1));
        assertEquals(2,page.getTotalElements());assertEquals(1,page.getContent().size());
        assertEquals(0,bankService.publicQuestions(null,owner.userId(),null,DifficultyLevel.HARD,PageRequest.of(0,20)).getTotalElements());
        assertEquals(1,bankService.listContributions(VerificationStatus.PUBLIC_PENDING,PageRequest.of(0,20),owner).getTotalElements());
        assertEquals(0,bankService.listContributions(null,PageRequest.of(0,20),other).getTotalElements());
        failure(404,()->bankService.contribution(pending.id(),other));
    }
    @Test void newHttpRoutesRespectSignedPrivilegeSafePayloadAndInternalBoundary() throws Exception {
        http.perform(post("/question-banks").header("Authorization",jwt(owner)).header("X-System-Privilege","ADMIN")
                .contentType("application/json").content("{\"type\":\"SYSTEM\",\"name\":\"Spoof\"}")).andExpect(status().isForbidden());
        var result=http.perform(post("/question-banks").header("Authorization",jwt(admin)).contentType("application/json")
                .content("{\"type\":\"SYSTEM\",\"name\":\"Signed admin bank\"}")).andExpect(status().isCreated()).andReturn();
        UUID bank=UUID.fromString(json.readTree(result.getResponse().getContentAsString()).get("id").asText());
        http.perform(post("/question-banks/"+bank+"/questions").header("Authorization",jwt(admin)).contentType("application/json")
                .content(json.writeValueAsString(input("HTTP system draft",QuestionScope.SYSTEM)))).andExpect(status().isCreated());
        http.perform(get("/question-banks/"+bank+"/questions").header("Authorization",jwt(owner))).andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        for(String path:List.of("/question-banks","/public-questions","/public-contributions")) {
            http.perform(get(path).header("X-Service-Token","isolated-test-service-token").header("X-Question-Owner-Id",owner.userId())).andExpect(status().isForbidden());
            http.perform(get(path).header("X-User-Id",owner.userId())).andExpect(status().isUnauthorized());
        }
        var q=approved("HTTP verified");var c=bankService.contribute(q.id(),new ContributionRequest(1),owner);
        http.perform(get("/public-contributions/"+c.id()).param("includeAnswers","true").header("Authorization",jwt(owner))).andExpect(status().isForbidden());
        http.perform(get("/public-contributions/"+c.id()).param("includeAnswers","true").header("Authorization",jwt(admin))).andExpect(status().isOk()).andExpect(jsonPath("$.question.options[0].correct").value(true));
        http.perform(post("/public-contributions/"+c.id()+"/review").header("Authorization",jwt(admin)).contentType("application/json")
                .content("{\"decision\":\"APPROVED\",\"reviewNote\":\"Verified\"}")).andExpect(status().isOk());
        String payload=http.perform(get("/public-questions").param("sourceOwnerUserId",owner.userId().toString()).header("Authorization",jwt(student)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(payload.contains("correct"));assertFalse(payload.contains("expectedAnswer"));assertFalse(payload.contains("sourceExcerpt"));
        http.perform(post("/questions/"+q.id()+"/fork").header("Authorization",jwt(owner)).contentType("application/json").content("{\"sourceQuestionVersion\":0}"))
                .andExpect(status().isBadRequest());
    }
    @Test void concurrentContributionAndReviewHaveSingleTerminalPublication() throws Exception {
        var q=approved("Concurrent contribution");
        int count=race(()->{bankService.contribute(q.id(),new ContributionRequest(1),owner);return true;});assertEquals(1,count);
        var c=bankService.listContributions(null,PageRequest.of(0,20),owner).getContent().getFirst();
        assertEquals(1,race(()->{bankService.reviewContribution(c.id(),new PublicReview(QuestionReview.Decision.APPROVED,"Concurrent"),admin);return true;}));
        assertEquals(1,bankService.publicQuestions(null,owner.userId(),null,null,PageRequest.of(0,20)).getTotalElements());
    }
    int race(Callable<Boolean> work) throws Exception {
        var gate=new CountDownLatch(1);try(var pool=Executors.newFixedThreadPool(2)) {
            var tasks=new ArrayList<Future<Boolean>>();for(int i=0;i<2;i++)tasks.add(pool.submit(()->{gate.await();try{return work.call();}catch(ResponseStatusException e){assertEquals(409,e.getStatusCode().value());return false;}}));
            gate.countDown();int success=0;for(var task:tasks)if(task.get(15,TimeUnit.SECONDS))success++;return success;
        }
    }
    @Test void upgradeFromPhase1PreservesOwnerOptionsVersionsReviewAndForeignKeys() throws Exception {
        String schema="qb2_upgrade_"+UUID.randomUUID().toString().replace("-","");
        String url=env.getRequiredProperty("spring.datasource.url"),user=env.getRequiredProperty("spring.datasource.username"),password=env.getRequiredProperty("spring.datasource.password");
        try {
            Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).target("2").load().migrate();
            try(var conn=DriverManager.getConnection(url,user,password)){conn.setSchema(schema);try(var sql=conn.createStatement()) {
                String snapshot;UUID id;UUID ownerId;UUID option;
                try(var rows=sql.executeQuery("SELECT v.snapshot,q.id,q.created_by,o.id FROM questions q JOIN question_versions v ON v.question_id=q.id JOIN question_options o ON o.question_id=q.id LIMIT 1")) {
                    assertTrue(rows.next());snapshot=rows.getString(1);id=(UUID)rows.getObject(2);ownerId=(UUID)rows.getObject(3);option=(UUID)rows.getObject(4);
                }
                sql.executeUpdate("INSERT INTO question_versions(id,question_id,version_number,snapshot,created_at,changed_by) SELECT gen_random_uuid(),question_id,2,jsonb_set(snapshot::jsonb,'{questionVersion}','2')::text,CURRENT_TIMESTAMP,changed_by FROM question_versions WHERE question_id='"+id+"' AND version_number=1");
                sql.executeUpdate("UPDATE questions SET status='APPROVED',question_version=2 WHERE id='"+id+"'");
                sql.executeUpdate("INSERT INTO question_reviews(id,question_id,question_version,reviewer_id,decision,review_note,reviewed_at) VALUES(gen_random_uuid(),'"+id+"',2,'"+admin.userId()+"','APPROVED','Before upgrade',CURRENT_TIMESTAMP)");
                Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).load().migrate();
                try(var rows=sql.executeQuery("SELECT q.created_by,b.owner_user_id,b.type,v.snapshot,q.status,q.question_version FROM questions q JOIN question_banks b ON b.id=q.bank_id JOIN question_versions v ON v.question_id=q.id WHERE q.id='"+id+"' AND v.version_number=1")) {
                    assertTrue(rows.next());assertEquals(ownerId,rows.getObject(1));assertEquals(ownerId,rows.getObject(2));assertEquals("PERSONAL",rows.getString(3));assertEquals(snapshot,rows.getString(4));assertEquals("APPROVED",rows.getString(5));
                    assertEquals(2,rows.getLong(6));
                }
                try(var rows=sql.executeQuery("SELECT count(*) FROM question_versions WHERE question_id='"+id+"'")){rows.next();assertEquals(2,rows.getInt(1));}
                try(var rows=sql.executeQuery("SELECT count(*) FROM question_options WHERE id='"+option+"'")){rows.next();assertEquals(1,rows.getInt(1));}
                try(var rows=sql.executeQuery("SELECT count(*) FROM question_reviews WHERE question_id='"+id+"'")){rows.next();assertEquals(1,rows.getInt(1));}
                assertThrows(java.sql.SQLException.class,()->sql.executeUpdate("UPDATE questions SET bank_id=gen_random_uuid() WHERE id='"+id+"'"));
            }}
        } finally {try(var conn=DriverManager.getConnection(url,user,password);var sql=conn.createStatement()){sql.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}}
    }
}

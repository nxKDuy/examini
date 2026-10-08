package com.datn.questionbank.service;

import com.datn.questionbank.dto.BankDto.*;
import com.datn.questionbank.dto.QuestionDto.*;
import com.datn.questionbank.entity.*;
import com.datn.questionbank.repository.*;
import com.datn.questionbank.security.QuestionActor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

import java.sql.DriverManager;
import java.util.*;

import static com.datn.questionbank.entity.Question.*;
import static com.datn.questionbank.entity.QuestionBank.BankType;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"eureka.client.enabled=false","spring.cloud.discovery.enabled=false",
        "jwt.secret=test-only-jwt-key-32-bytes-or-longer-never-production",
        "question-bank.internal-token=isolated-test-service-token"})
@EnabledIfEnvironmentVariable(named="QB_TEST_DATABASE",matches="true")
@Transactional
class MultiBankPersistenceTest {
    @Autowired MultiBankService banks;
    @Autowired OrganizationBankService organizations;
    @Autowired QuestionService questions;
    @Autowired ExamEligibilityService eligibility;
    @Autowired OrganizationQuestionRepository organizationRepo;
    @Autowired QuestionRepository questionRepo;
    @Autowired CategoryRepository categories;
    @Autowired ObjectMapper json;
    @Autowired Environment environment;
    @MockBean OrganizationAuthorizationClient organizationAuthorization;

    QuestionActor owner=new QuestionActor(UUID.randomUUID(),"TEACHER",false);
    QuestionActor reviewer=new QuestionActor(UUID.randomUUID(),"TEACHER",false);
    QuestionActor systemAdmin=new QuestionActor(UUID.randomUUID(),"ADMIN",false,"ADMIN");
    UUID category;

    @BeforeEach void setup(){category=categories.findAll().getFirst().getId();}
    CreateRequest input(String content,QuestionScope scope){return new CreateRequest(category,content,QuestionType.MULTIPLE_CHOICE,
            DifficultyLevel.EASY,"Book",List.of(new OptionRequest("Yes",true,(short)0),new OptionRequest("No",false,(short)1)),
            null,scope,SourceType.USER_DOCUMENT,"doc:1","Book",null,"Private excerpt");}
    QuestionResponse active(String content){var q=questions.create(input(content,QuestionScope.PERSONAL),owner);return questions.activate(q.id(),1,owner);}
    UpdateRequest edit(String content,long version){return new UpdateRequest(null,content,null,null,null,null,null,null,null,null,null,null,version);}
    void fails(int status,Executable call){assertEquals(status,assertThrows(ResponseStatusException.class,call::run).getStatusCode().value());}
    interface Executable {void run();}

    @Test void personalOwnerActivatesUsesAndVersionsWithoutReview(){
        var q=active("Personal active");
        questionRepo.flush();
        assertEquals(List.of(q.id()),eligibility.random(category,DifficultyLevel.EASY,10,null,null,owner));
        var candidate=eligibility.batch(List.of(q.id()),false,owner).getFirst();
        assertTrue(candidate.eligible());assertEquals(BankType.PERSONAL,candidate.bankType());
        var edited=questions.update(q.id(),edit("Independent v2",1),owner);
        assertEquals(QuestionStatus.DRAFT,edited.status());assertEquals(2,edited.questionVersion());
        fails(409,()->eligibility.batch(List.of(q.id()),false,owner));
        assertEquals("Personal active",questions.version(q.id(),1,true,owner).question().content());
    }

    @Test void publicPublishIsImmediateFrozenCandidateSafeAndForkIndependent() throws Exception {
        var q=active("Public frozen v1");
        var publication=banks.publish(q.id(),new ContributionRequest(1),owner);
        assertEquals("PUBLISHED",publication.publicationStatus());assertNotEquals(q.id(),publication.id());
        String safe=json.writeValueAsString(publication);assertFalse(safe.contains("correct"));assertFalse(safe.contains("expectedAnswer"));
        questions.update(q.id(),edit("Private v2",1),owner);
        assertEquals("Public frozen v1",banks.publicQuestion(publication.id()).question().content());
        var candidate=eligibility.batch(List.of(publication.id()),false,owner).getFirst();
        assertTrue(candidate.eligible());assertEquals(BankType.PUBLIC,candidate.bankType());assertEquals("PUBLISHED",candidate.publicationStatus());
        var fork=banks.forkPublic(publication.id(),new ForkRequest(1,null),reviewer).question();
        assertEquals(QuestionStatus.DRAFT,fork.status());assertEquals("Public frozen v1",fork.content());
        var lineage=banks.lineage(fork.id(),reviewer);assertEquals(publication.id(),lineage.sourcePublicQuestionId());assertEquals(1,lineage.sourceQuestionVersion());
        questions.update(fork.id(),edit("Fork changed",1),reviewer);
        assertEquals("Public frozen v1",banks.publicQuestion(publication.id()).question().content());
    }

    @Test void systemBankRequiresSignedPrivilegeAndTeacherCannotMutate(){
        fails(403,()->banks.createBank(new CreateBank(BankType.SYSTEM,"System",null),owner));
        var bank=banks.createBank(new CreateBank(BankType.SYSTEM,"System",null),systemAdmin);
        var q=banks.createQuestion(bank.id(),input("System",QuestionScope.SYSTEM),systemAdmin).question();
        fails(403,()->questions.activate(q.id(),1,owner));
        questions.activate(q.id(),1,systemAdmin);
        assertTrue(eligibility.batch(List.of(q.id()),false,owner).getFirst().eligible());
        fails(403,()->eligibility.batch(List.of(q.id()),true,owner));
    }

    @Test void organizationContributionHasIndependentReviewAndFrozenPublication(){
        UUID organizationId=UUID.randomUUID();
        var bank=banks.createBank(new CreateBank(BankType.ORGANIZATION,"Org",organizationId),owner);
        var source=active("Org source v1");
        var pending=organizations.submit(source.id(),new OrganizationContributionRequest(bank.id(),1),owner);
        assertEquals(OrganizationContribution.ContributionStatus.PENDING_REVIEW,pending.status());
        fails(403,()->organizations.review(pending.id(),new PublicReview(QuestionReview.Decision.APPROVED,"self"),owner));
        var approved=organizations.review(pending.id(),new PublicReview(QuestionReview.Decision.APPROVED,"OK"),reviewer);
        assertEquals(OrganizationContribution.ContributionStatus.APPROVED,approved.status());
        assertEquals(QuestionStatus.ACTIVE,questions.findById(source.id(),false,owner).status());
        var publication=organizationRepo.findAll().stream().filter(p->p.getContributionId().equals(pending.id())).findFirst().orElseThrow();
        questions.update(source.id(),edit("Source v2",1),owner);
        assertEquals("Org source v1",organizations.publication(publication.getId(),reviewer).question().content());
        var eligible=eligibility.batch(List.of(publication.getId()),false,reviewer).getFirst();
        assertTrue(eligible.eligible());assertEquals("APPROVED",eligible.publicationStatus());
    }

    @Test void organizationRejectionLeavesSourceUsableAndProviderDenialFailsClosed(){
        UUID organizationId=UUID.randomUUID();
        var bank=banks.createBank(new CreateBank(BankType.ORGANIZATION,"Org reject",organizationId),owner);
        var source=active("Rejected source");
        var pending=organizations.submit(source.id(),new OrganizationContributionRequest(bank.id(),1),owner);
        var rejected=organizations.review(pending.id(),new PublicReview(QuestionReview.Decision.REJECTED,"Needs work"),reviewer);
        assertEquals(OrganizationContribution.ContributionStatus.REJECTED,rejected.status());
        assertEquals(QuestionStatus.ACTIVE,questions.findById(source.id(),false,owner).status());
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(organizationAuthorization)
                .require(eq(organizationId),eq(owner.userId()),any());
        fails(403,()->organizations.submit(source.id(),new OrganizationContributionRequest(bank.id(),1),owner));
    }

    @Test void publicationAnswerKeyOnlyUsesServerCredentialLane() throws Exception {
        var q=active("Public key");var publication=banks.publish(q.id(),new ContributionRequest(1),owner);
        fails(403,()->eligibility.batch(List.of(publication.id()),true,owner));
        var internal=new QuestionActor(owner.userId(),"SERVICE",true);
        String full=json.writeValueAsString(eligibility.batch(List.of(publication.id()),true,internal));
        assertTrue(full.contains("\"correct\":true"));
        String candidate=json.writeValueAsString(eligibility.batch(List.of(publication.id()),false,internal));
        assertFalse(candidate.contains("correct"));assertFalse(candidate.contains("expectedAnswer"));
    }

    @Test void upgradeMapsLegacyCurrentStatusWithoutChangingSnapshots() throws Exception {
        String schema="qb_lifecycle_"+UUID.randomUUID().toString().replace("-","");
        String url=environment.getRequiredProperty("spring.datasource.url");
        String user=environment.getRequiredProperty("spring.datasource.username");
        String password=environment.getRequiredProperty("spring.datasource.password");
        try {
            Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).target("3").load().migrate();
            try(var connection=DriverManager.getConnection(url,user,password)){connection.setSchema(schema);try(var sql=connection.createStatement()){
                var ids=new ArrayList<UUID>();var snapshots=new HashMap<UUID,String>();
                try(var rows=sql.executeQuery("SELECT q.id,v.snapshot FROM questions q JOIN question_versions v ON v.question_id=q.id ORDER BY q.id")){
                    while(rows.next()){var id=(UUID)rows.getObject(1);ids.add(id);snapshots.put(id,rows.getString(2));}
                }
                sql.executeUpdate("UPDATE questions SET status='APPROVED' WHERE id='"+ids.get(0)+"'");
                sql.executeUpdate("UPDATE questions SET status='REJECTED' WHERE id='"+ids.get(1)+"'");
                Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).load().migrate();
                try(var rows=sql.executeQuery("SELECT q.id,q.status,m.previous_status,v.snapshot FROM questions q JOIN question_lifecycle_migrations m ON m.question_id=q.id JOIN question_versions v ON v.question_id=q.id")){
                    while(rows.next()){
                        UUID id=(UUID)rows.getObject(1);String before=rows.getString(3);
                        String expected=switch(before){case "APPROVED"->"ACTIVE";case "PENDING_REVIEW","REJECTED"->"DRAFT";default->before;};
                        assertEquals(expected,rows.getString(2));assertEquals(snapshots.get(id),rows.getString(4));
                    }
                }
            }}
        } finally {try(var connection=DriverManager.getConnection(url,user,password);var sql=connection.createStatement()){sql.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}}
    }
}

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
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import jakarta.validation.Validator;
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
    @Autowired QuestionSetService sets;
    @Autowired OrganizationBankService organizations;
    @Autowired QuestionService questions;
    @Autowired ExamEligibilityService eligibility;
    @Autowired OrganizationQuestionRepository organizationRepo;
    @Autowired QuestionRepository questionRepo;
    @Autowired CategoryRepository categories;
    @Autowired SubjectRepository subjectRepo;
    @Autowired QuestionBankRepository bankRepo;
    @Autowired ObjectMapper json;
    @Autowired Environment environment;
    @Autowired Validator validator;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @MockBean OrganizationAuthorizationClient organizationAuthorization;
    @MockBean AuthIdentityClient identities;

    QuestionActor owner=new QuestionActor(UUID.randomUUID(),"TEACHER",false);
    QuestionActor reviewer=new QuestionActor(UUID.randomUUID(),"TEACHER",false);
    QuestionActor systemAdmin=new QuestionActor(UUID.randomUUID(),"ADMIN",false,"ADMIN");
    UUID category;

    @BeforeEach void setup(){category=categories.findAll().getFirst().getId();
        when(identities.displayName(any())).thenReturn("Cô Lan");}
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
        assertEquals(PublicQuestion.PublicationStatus.PUBLISHED,publication.publicationStatus());assertNotEquals(q.id(),publication.id());
        String safe=json.writeValueAsString(publication);assertFalse(safe.contains("correct"));assertFalse(safe.contains("expectedAnswer"));
        questions.update(q.id(),edit("Private v2",1),owner);
        assertEquals("Public frozen v1",banks.publicQuestion(publication.id(),reviewer).question().content());
        var candidate=eligibility.batch(List.of(publication.id()),false,owner).getFirst();
        assertTrue(candidate.eligible());assertEquals(BankType.PUBLIC,candidate.bankType());assertEquals("PUBLISHED",candidate.publicationStatus());
        var fork=banks.forkPublic(publication.id(),new ForkRequest(1,null),reviewer).question();
        assertEquals(QuestionStatus.DRAFT,fork.status());assertEquals("Public frozen v1",fork.content());
        var lineage=banks.lineage(fork.id(),reviewer);assertEquals(publication.id(),lineage.sourcePublicQuestionId());assertEquals(1,lineage.sourceQuestionVersion());
        questions.update(fork.id(),edit("Fork changed",1),reviewer);
        assertEquals("Public frozen v1",banks.publicQuestion(publication.id(),reviewer).question().content());
    }

    @Test void personalSetVersionsPublishFrozenItemsAndForkSingleOrWholeSet() throws Exception {
        var one=active("Set frozen one");var two=active("Set frozen two");
        var bank=bankRepo.findByOwnerUserIdAndDefaultBankTrue(owner.userId()).orElseThrow();
        var created=sets.create(bank.getId(),new QuestionSetWrite("Algebra set","description",List.of(
                new SetItemRequest(one.id(),1L),new SetItemRequest(two.id(),1L)),null),owner);
        assertEquals(2,created.items().size());assertEquals(1,created.version());
        var postV1=sets.publish(created.id(),new PublishSetRequest(1L),owner);
        assertEquals(2,postV1.items().size());assertEquals(1,postV1.postVersion());
        var edited=sets.update(created.id(),new QuestionSetWrite("Algebra set v2","updated",List.of(
                new SetItemRequest(two.id(),1L)),1L),owner);
        assertEquals(2,edited.version());assertEquals(1,edited.items().size());
        assertEquals(2,sets.version(created.id(),1,owner).items().size());
        var post=sets.publish(created.id(),new PublishSetRequest(2L),owner);
        assertEquals(PublicQuestion.PostType.QUESTION_SET,post.postType());assertEquals(2,post.postVersion());assertEquals(1,post.itemCount());
        assertNotEquals(postV1.id(),post.id());assertEquals(2,banks.publicQuestion(postV1.id(),owner).itemCount());
        String safe=json.writeValueAsString(post);assertFalse(safe.contains("correct"));assertFalse(safe.contains("expectedAnswer"));assertFalse(safe.contains("@"));
        var item=post.items().getFirst();var selected=eligibility.batch(List.of(item.id()),false,new QuestionActor(UUID.randomUUID(),"STUDENT",false)).getFirst();
        assertTrue(selected.eligible());assertEquals(item.id(),selected.id());assertEquals("Set frozen two",selected.content());
        var candidate=json.writeValueAsString(eligibility.batch(List.of(item.id()),false,new QuestionActor(UUID.randomUUID(),"STUDENT",false)));
        assertFalse(candidate.contains("correct"));assertFalse(candidate.contains("expectedAnswer"));
        var grading=json.writeValueAsString(eligibility.batch(List.of(item.id()),true,new QuestionActor(UUID.randomUUID(),"SERVICE",true)));
        assertTrue(grading.contains("\"correct\":true"));
        fails(403,()->sets.fork(post.id(),new QuestionSetForkRequest(null),new QuestionActor(UUID.randomUUID(),"STUDENT",false)));
        var copied=sets.fork(post.id(),new QuestionSetForkRequest(null),reviewer);
        assertEquals(1,copied.items().size());assertEquals("Set frozen two",copied.items().getFirst().question().content());
        var itemCopy=banks.forkPublicItem(post.id(),item.id(),new ForkRequest(item.sourceQuestionVersion(),null),reviewer);
        assertEquals("Set frozen two",itemCopy.question().content());
        var itemLineage=banks.lineage(itemCopy.question().id(),reviewer);
        assertEquals(two.id(),itemLineage.sourceQuestionId());assertEquals(1,itemLineage.sourceQuestionVersion());
        questions.update(two.id(),edit("Source after publish",1),owner);
        assertEquals("Set frozen two",banks.publicQuestion(post.id(),new QuestionActor(UUID.randomUUID(),"STUDENT",false)).items().getFirst().question().content());
        assertNotEquals(itemCopy.question().id(),item.sourceQuestionId());
        banks.archivePublic(post.id(),owner);
        var mine=banks.publicQuestions(null,null,null,null,null,PublicQuestion.PublicationStatus.ARCHIVED,true,null,null,PublicQuestion.PostType.QUESTION_SET,"NEWEST",org.springframework.data.domain.PageRequest.of(0,20),owner);
        assertEquals(post.id(),mine.getContent().stream().filter(p->p.id().equals(post.id())).findFirst().orElseThrow().id());
        fails(404,()->banks.publicQuestion(post.id(),new QuestionActor(UUID.randomUUID(),"STUDENT",false)));
        fails(409,()->eligibility.batch(List.of(item.id()),false,reviewer));
    }

    @Test void publicDiscoveryFiltersSetsAndCountsDistinctNonOwnerCopiesByRollingWindow() {
        var q=active("Ranking content");var single=banks.publish(q.id(),new ContributionRequest(1),owner);
        var bank=bankRepo.findByOwnerUserIdAndDefaultBankTrue(owner.userId()).orElseThrow();
        var set=sets.create(bank.getId(),new QuestionSetWrite("Ranking set",null,List.of(new SetItemRequest(q.id(),1L)),null),owner);
        var post=sets.publish(set.id(),new PublishSetRequest(1L),owner);
        var actorA=new QuestionActor(UUID.randomUUID(),"TEACHER",false);var actorB=new QuestionActor(UUID.randomUUID(),"TEACHER",false);
        banks.forkPublic(single.id(),new ForkRequest(1,null),actorA);
        banks.forkPublic(single.id(),new ForkRequest(1,null),actorA); // retries/duplicates do not inflate distinct actors
        banks.forkPublic(single.id(),new ForkRequest(1,null),actorB);
        jdbc.update("INSERT INTO public_question_copy_events(publication_id,actor_user_id,copied_at) VALUES(:post,:actor,CURRENT_TIMESTAMP-INTERVAL '40 days')",
                new org.springframework.jdbc.core.namedparam.MapSqlParameterSource().addValue("post",single.id()).addValue("actor",UUID.randomUUID()));
        banks.forkPublic(single.id(),new ForkRequest(1,null),owner); // self-copy excluded
        sets.fork(post.id(),new QuestionSetForkRequest(null),actorA);
        var page=org.springframework.data.domain.PageRequest.of(0,20);
        var top=banks.publicQuestions(null,null,null,null,null,null,false,null,null,null,"NEWEST",page,owner);
        var counted=top.getContent().stream().filter(p->p.id().equals(single.id())).findFirst().orElseThrow();
        assertEquals(2,counted.copies24h());assertEquals(2,counted.copies30d());assertEquals(3,counted.copies365d());
        assertEquals(1,top.getContent().stream().filter(p->p.id().equals(post.id())).findFirst().orElseThrow().copies24h());
        assertEquals(1,banks.publicQuestions(null,null,null,null,null,null,false,null,null,PublicQuestion.PostType.QUESTION_SET,"NEWEST",page,owner).getTotalElements());
        fails(409,()->banks.publicQuestions(null,null,null,null,null,null,false,null,null,null,"DAY",page,owner));
        assertEquals(1,banks.publicQuestions(null,null,null,null,null,null,false,null,category,PublicQuestion.PostType.QUESTION_SET,"NEWEST",page,owner).getTotalElements());
        assertEquals(post.id(),banks.publicQuestions(null,null,null,null,null,null,false,null,null,PublicQuestion.PostType.QUESTION_SET,"NEWEST",page,owner).getContent().getFirst().id());
    }

    @Test void setCanSpanSubjectsAndCategoriesAndDiscoveryFiltersAnyItemOnce() {
        var first=active("Math topic");
        var biology=subjectRepo.save(Subject.builder().name("Biology").description("Test taxonomy").build());
        var biologyCategory=categories.save(Category.builder().subject(biology).name("Plants").build());
        var second=questions.create(new CreateRequest(biologyCategory.getId(),"Plant topic",QuestionType.MULTIPLE_CHOICE,
                DifficultyLevel.EASY,"Book",List.of(new OptionRequest("Yes",true,(short)0),new OptionRequest("No",false,(short)1)),
                null,QuestionScope.PERSONAL,SourceType.NONE,null,null,null,null),owner);
        questions.activate(second.id(),1,owner);
        var bank=bankRepo.findByOwnerUserIdAndDefaultBankTrue(owner.userId()).orElseThrow();
        var set=sets.create(bank.getId(),new QuestionSetWrite("Cross subject set",null,
                List.of(new SetItemRequest(first.id(),1L),new SetItemRequest(second.id(),1L)),null),owner);
        var post=sets.publish(set.id(),new PublishSetRequest(1L),owner);
        assertEquals(2,post.subjects().size());assertEquals(2,post.categories().size());
        var page=org.springframework.data.domain.PageRequest.of(0,20);
        assertEquals(1,banks.publicQuestions(null,null,null,null,null,null,false,biology.getId(),null,
                PublicQuestion.PostType.QUESTION_SET,"NEWEST",page,owner).getTotalElements());
        assertEquals(1,banks.publicQuestions(null,null,null,null,null,null,false,null,biologyCategory.getId(),
                PublicQuestion.PostType.QUESTION_SET,"NEWEST",page,owner).getTotalElements());
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
        var pending=organizations.submit(source.id(),new OrganizationContributionRequest(bank.id(),1,null),owner);
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

    @Test void organizationSubmissionNoteIsOptionalBoundedAndSeparateFromReviewNote(){
        UUID organizationId=UUID.randomUUID();
        var bank=banks.createBank(new CreateBank(BankType.ORGANIZATION,"Org note",organizationId),owner);
        var source=active("Contribution with note");
        var request=new OrganizationContributionRequest(bank.id(),1,"  Please preserve diagram context  ");
        assertTrue(validator.validate(request).isEmpty());
        assertFalse(validator.validate(new OrganizationContributionRequest(bank.id(),1,"x".repeat(5001))).isEmpty());
        var pending=organizations.submit(source.id(),request,owner);
        assertEquals("Please preserve diagram context",pending.submissionNote());
        assertNull(pending.reviewNote());
        assertEquals(pending.submissionNote(),organizations.contribution(pending.id(),false,owner).submissionNote());
        var mine=organizations.contributions(bank.id(),null,true,org.springframework.data.domain.PageRequest.of(0,20),owner);
        assertEquals("Please preserve diagram context",mine.getContent().getFirst().submissionNote());
        var rejected=organizations.review(pending.id(),new PublicReview(QuestionReview.Decision.REJECTED,"Missing citation"),reviewer);
        assertEquals("Please preserve diagram context",rejected.submissionNote());
        assertEquals("Missing citation",rejected.reviewNote());
    }

    @Test void organizationRejectionLeavesSourceUsableAndProviderDenialFailsClosed(){
        UUID organizationId=UUID.randomUUID();
        var bank=banks.createBank(new CreateBank(BankType.ORGANIZATION,"Org reject",organizationId),owner);
        var source=active("Rejected source");
        var pending=organizations.submit(source.id(),new OrganizationContributionRequest(bank.id(),1,null),owner);
        var rejected=organizations.review(pending.id(),new PublicReview(QuestionReview.Decision.REJECTED,"Needs work"),reviewer);
        assertEquals(OrganizationContribution.ContributionStatus.REJECTED,rejected.status());
        assertEquals(QuestionStatus.ACTIVE,questions.findById(source.id(),false,owner).status());
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(organizationAuthorization)
                .require(eq(organizationId),eq(owner.userId()),any());
        fails(403,()->organizations.submit(source.id(),new OrganizationContributionRequest(bank.id(),1,null),owner));
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

    @Test void publicDiscoveryBrowseSearchArchiveAndRepublishKeepLineage() throws Exception {
        var student=new QuestionActor(UUID.randomUUID(),"STUDENT",false);
        var q=active("Photosynthesis basics");
        var v1=banks.publish(q.id(),new ContributionRequest(1),owner);
        assertEquals("Cô Lan",v1.sourceOwnerDisplayName());
        String body=json.writeValueAsString(banks.publicQuestion(v1.id(),student));
        assertFalse(body.contains("correct"));assertFalse(body.contains("expectedAnswer"));assertFalse(body.contains("@"));
        var page=org.springframework.data.domain.PageRequest.of(0,20);
        assertEquals(1,banks.publicQuestions(null,null,null,null,"photosyn",null,false,page,owner).getTotalElements());
        assertEquals(0,banks.publicQuestions(null,null,null,null,"100%",null,false,page,owner).getTotalElements());
        var fork=banks.forkPublic(v1.id(),new ForkRequest(1,null),reviewer).question();
        fails(403,()->banks.forkPublic(v1.id(),new ForkRequest(1,null),student));
        fails(404,()->banks.archivePublic(v1.id(),reviewer));
        banks.archivePublic(v1.id(),owner);questionRepo.flush();
        assertEquals(0,banks.publicQuestions(null,null,null,null,"photosyn",null,false,page,owner).getTotalElements());
        var ownerHistory=banks.publicQuestions(null,null,null,null,null,null,true,page,owner);
        assertEquals(1,ownerHistory.getTotalElements());
        assertEquals(PublicQuestion.PublicationStatus.ARCHIVED,ownerHistory.getContent().getFirst().publicationStatus());
        assertEquals(1,banks.publicQuestions(null,null,null,null,null,PublicQuestion.PublicationStatus.ARCHIVED,true,page,owner).getTotalElements());
        fails(403,()->banks.publicQuestions(null,owner.userId(),null,null,null,PublicQuestion.PublicationStatus.ARCHIVED,false,page,student));
        fails(403,()->banks.publicQuestions(null,owner.userId(),null,null,null,null,true,page,student));
        fails(404,()->banks.publicQuestion(v1.id(),student));
        assertEquals(PublicQuestion.PublicationStatus.ARCHIVED,banks.publicQuestion(v1.id(),owner).publicationStatus());
        fails(409,()->banks.forkPublic(v1.id(),new ForkRequest(1,null),reviewer));
        fails(409,()->eligibility.batch(List.of(v1.id()),false,owner));
        assertEquals(List.of(),eligibility.random(category,DifficultyLevel.EASY,10,null,banks.listBanks(BankType.PUBLIC,null,null,page,owner).getContent().getFirst().id(),student));
        fails(409,()->banks.publish(q.id(),new ContributionRequest(1),owner));
        assertEquals(v1.id(),banks.lineage(fork.id(),reviewer).sourcePublicQuestionId());
        questions.update(q.id(),edit("Photosynthesis v2",1),owner);questions.activate(q.id(),2,owner);
        var v2=banks.publish(q.id(),new ContributionRequest(2),owner);
        assertNotEquals(v1.id(),v2.id());assertEquals(2,v2.sourceQuestionVersion());
        assertEquals(1,banks.publicQuestions(null,null,null,null,"photosynthesis",null,false,page,owner).getTotalElements());
    }

    @Test void lineageMetadataIsSafeAndHidesArchivedOrRevokedPublication() throws Exception {
        var source=active("Lineage source");
        var publication=banks.publish(source.id(),new ContributionRequest(1),owner);
        var fork=banks.forkPublic(publication.id(),new ForkRequest(1,null),reviewer).question();
        var visible=banks.lineage(fork.id(),reviewer);
        assertEquals(List.of(LineageRelation.SOURCE,LineageRelation.PUBLICATION,LineageRelation.COPY),visible.nodes().stream().map(LineageNode::relation).toList());
        assertTrue(visible.nodes().stream().allMatch(LineageNode::available));
        assertEquals("PUBLISHED",visible.nodes().get(1).status());
        String safe=json.writeValueAsString(visible);
        assertFalse(safe.contains("correct"));assertFalse(safe.contains("expectedAnswer"));assertFalse(safe.contains("@"));
        banks.archivePublic(publication.id(),owner);
        var hidden=banks.lineage(fork.id(),reviewer);
        assertFalse(hidden.nodes().get(0).available());assertNull(hidden.nodes().get(0).status());assertNull(hidden.nodes().get(0).name());
        assertFalse(hidden.nodes().get(1).available());assertNull(hidden.nodes().get(1).status());assertNull(hidden.nodes().get(1).name());
        assertTrue(hidden.nodes().get(2).available());
    }

    @Test void lineageNeverLeaksPrivatePersonalBankNameAcrossPublicOrOrganizationForks(){
        var source=active("Private source name");
        var personal=bankRepo.findByOwnerUserIdAndDefaultBankTrue(owner.userId()).orElseThrow();
        personal.setName("PRIVATE OWNER BANK NAME");bankRepo.saveAndFlush(personal);
        var publicPublication=banks.publish(source.id(),new ContributionRequest(1),owner);
        var publicFork=banks.forkPublic(publicPublication.id(),new ForkRequest(1,null),reviewer).question();
        var publicLineage=banks.lineage(publicFork.id(),reviewer);
        assertEquals("PRIVATE OWNER BANK NAME",banks.lineage(
                banks.forkPublic(publicPublication.id(),new ForkRequest(1,null),owner).question().id(),owner)
                .nodes().get(0).name());
        assertNull(publicLineage.nodes().get(0).name());
        assertEquals(QuestionStatus.ACTIVE,QuestionStatus.valueOf(publicLineage.nodes().get(0).status()));
        assertTrue(publicLineage.nodes().get(0).frozen());

        UUID organizationId=UUID.randomUUID();
        var organization=banks.createBank(new CreateBank(BankType.ORGANIZATION,"Org bank",organizationId),owner);
        var pending=organizations.submit(source.id(),new OrganizationContributionRequest(organization.id(),1,null),owner);
        organizations.review(pending.id(),new PublicReview(QuestionReview.Decision.APPROVED,"OK"),reviewer);
        var publication=organizationRepo.findAll().stream().filter(p->p.getContributionId().equals(pending.id())).findFirst().orElseThrow();
        var orgFork=organizations.fork(publication.getId(),new ForkRequest(1,null),reviewer).question();
        var orgLineage=banks.lineage(orgFork.id(),reviewer);
        assertNull(orgLineage.nodes().get(0).name());
        assertEquals("APPROVED",orgLineage.nodes().get(1).status());
        assertEquals("PRIVATE OWNER BANK NAME",banks.lineage(
                organizations.fork(publication.getId(),new ForkRequest(1,null),owner).question().id(),owner)
                .nodes().get(0).name());
    }

    @Test void organizationLineageHidesPublicationAfterMembershipRevocation(){
        UUID organizationId=UUID.randomUUID();
        var bank=banks.createBank(new CreateBank(BankType.ORGANIZATION,"Org provenance",organizationId),owner);
        var source=active("Org lineage source");
        var pending=organizations.submit(source.id(),new OrganizationContributionRequest(bank.id(),1,null),owner);
        organizations.review(pending.id(),new PublicReview(QuestionReview.Decision.APPROVED,"OK"),reviewer);
        var publication=organizationRepo.findAll().stream().filter(p->p.getContributionId().equals(pending.id())).findFirst().orElseThrow();
        var fork=organizations.fork(publication.getId(),new ForkRequest(1,null),reviewer).question();
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(organizationAuthorization)
                .require(eq(organizationId),eq(reviewer.userId()),eq(OrganizationAuthorizationClient.Capability.BANK_READ));
        var hidden=banks.lineage(fork.id(),reviewer);
        assertFalse(hidden.nodes().get(0).available());assertFalse(hidden.nodes().get(1).available());
        assertNull(hidden.nodes().get(1).status());assertNull(hidden.nodes().get(1).name());
        assertTrue(hidden.nodes().get(2).available());
    }

    @Test void publishFailsClosedWhenAuthorIdentityUnavailable(){
        when(identities.displayName(any())).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        var q=active("No identity");
        fails(503,()->banks.publish(q.id(),new ContributionRequest(1),owner));
        assertEquals(0,banks.publicQuestions(null,null,null,null,"No identity",null,false,org.springframework.data.domain.PageRequest.of(0,20),owner).getTotalElements());
    }

    @Test void organizationBankListUsesProviderMembershipsOnly(){
        UUID member=UUID.randomUUID(),other=UUID.randomUUID();
        var mine=banks.createBank(new CreateBank(BankType.ORGANIZATION,"Mine",member),owner);
        banks.createBank(new CreateBank(BankType.ORGANIZATION,"Other",other),owner);
        when(organizationAuthorization.memberships(eq(owner.userId()),any())).thenReturn(List.of(
                new OrganizationAuthorizationClient.Membership(member,"M","ARCHIVED",UUID.randomUUID(),"ACTIVE","MEMBER",false,
                        List.of(OrganizationAuthorizationClient.Capability.BANK_READ))));
        var page=org.springframework.data.domain.PageRequest.of(0,50);
        var ids=banks.listBanks(BankType.ORGANIZATION,null,null,page,owner).map(BankResponse::id).getContent();
        assertEquals(List.of(mine.id()),ids);
        when(organizationAuthorization.memberships(eq(owner.userId()),any())).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));
        fails(503,()->banks.listBanks(BankType.ORGANIZATION,null,null,page,owner));
        fails(503,()->banks.listBanks(null,null,null,page,owner));
    }

    @Test void organizationRevokeAndArchiveBlockSubmitReviewAndSelection(){
        UUID organizationId=UUID.randomUUID();
        var bank=banks.createBank(new CreateBank(BankType.ORGANIZATION,"Org live",organizationId),owner);
        var source=active("Org live source");
        var pending=organizations.submit(source.id(),new OrganizationContributionRequest(bank.id(),1,null),owner);
        var approved=organizations.review(pending.id(),new PublicReview(QuestionReview.Decision.APPROVED,"OK"),reviewer);
        var pub=organizationRepo.findAll().stream().filter(p->p.getContributionId().equals(approved.id())).findFirst().orElseThrow();
        // membership revoked: provider denies every capability on the next call, no cached allow
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(organizationAuthorization).require(eq(organizationId),eq(reviewer.userId()),any());
        fails(403,()->eligibility.batch(List.of(pub.getId()),false,reviewer));
        fails(403,()->organizations.publication(pub.getId(),reviewer));
        // archived org: READ only, USE denied
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN)).when(organizationAuthorization).require(eq(organizationId),eq(owner.userId()),
                argThat(c->c!=OrganizationAuthorizationClient.Capability.BANK_READ));
        assertEquals(pub.getId(),organizations.publication(pub.getId(),owner).id());
        fails(403,()->eligibility.batch(List.of(pub.getId()),false,owner));
        fails(403,()->organizations.fork(pub.getId(),new ForkRequest(1,null),owner));
        // the already-created Personal fork stays independent
        assertEquals(QuestionStatus.ACTIVE,questions.findById(source.id(),false,owner).status());
    }

    @Test void migrationV4ToV5BackfillsExistingPublicationsAsPublished() throws Exception {
        String schema="qb_public_"+UUID.randomUUID().toString().replace("-","");
        String url=environment.getRequiredProperty("spring.datasource.url");
        String user=environment.getRequiredProperty("spring.datasource.username");
        String password=environment.getRequiredProperty("spring.datasource.password");
        try {
            Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).target("4").load().migrate();
            try(var connection=DriverManager.getConnection(url,user,password)){connection.setSchema(schema);try(var sql=connection.createStatement()){
                UUID id;String snapshot;
                try(var rows=sql.executeQuery("SELECT q.id,q.bank_id,v.snapshot FROM questions q JOIN question_versions v ON v.question_id=q.id LIMIT 1")){
                    rows.next();id=(UUID)rows.getObject(1);snapshot=rows.getString(3).replace("'","''");
                    var bank=(UUID)rows.getObject(2);
                    var publicBank=new UUID[1];
                    sql.getConnection().setAutoCommit(true);
                    try(var b=connection.createStatement();var r=b.executeQuery("SELECT id FROM question_banks WHERE type='PUBLIC'")){r.next();publicBank[0]=(UUID)r.getObject(1);}
                    var contribution=UUID.randomUUID();
                    try(var b=connection.createStatement()){
                        b.executeUpdate("INSERT INTO public_contributions(id,source_question_id,source_question_version,source_bank_id,source_owner_user_id,submitted_by,submitted_at,snapshot,status,reviewer_id,reviewed_at) VALUES('"+contribution+"','"+id+"',1,'"+bank+"','"+UUID.randomUUID()+"','"+UUID.randomUUID()+"',now(),'"+snapshot+"','PUBLIC_VERIFIED','"+UUID.randomUUID()+"',now())");
                        b.executeUpdate("INSERT INTO public_questions(bank_id,contribution_id,source_question_id,source_question_version,source_bank_id,source_owner_user_id,snapshot,question_type,difficulty_level,verified_by,verified_at) SELECT '"+publicBank[0]+"','"+contribution+"',source_question_id,1,source_bank_id,source_owner_user_id,snapshot,'MULTIPLE_CHOICE','EASY',reviewer_id,now() FROM public_contributions WHERE id='"+contribution+"'");
                    }
                }
                Flyway.configure().dataSource(url,user,password).schemas(schema).defaultSchema(schema).load().migrate();
                try(var columns=sql.executeQuery("SELECT count(*) FROM information_schema.columns WHERE table_schema='"+schema+"' AND table_name='organization_contributions' AND column_name='submission_note'")){
                    assertTrue(columns.next());assertEquals(1,columns.getInt(1));
                }
                try(var rows=sql.executeQuery("SELECT status,source_owner_display_name,search_content,archived_at FROM public_questions")){
                    assertTrue(rows.next());assertEquals("PUBLISHED",rows.getString(1));assertEquals("Unknown user",rows.getString(2));
                    assertNotNull(rows.getString(3));assertNull(rows.getTimestamp(4));
                }
                try(var rows=sql.executeQuery("SELECT p.id,p.post_type,p.item_count,i.id,i.snapshot FROM public_questions p JOIN public_question_items i ON i.publication_id=p.id")){
                    assertTrue(rows.next());assertEquals(rows.getObject(1),rows.getObject(4));assertEquals("SINGLE_QUESTION",rows.getString(2));
                    assertEquals(1,rows.getInt(3));assertNotNull(rows.getString(5));
                }
                assertThrows(java.sql.SQLException.class,()->sql.executeUpdate("UPDATE public_questions SET status='ARCHIVED'"));
            }}
        } finally {try(var connection=DriverManager.getConnection(url,user,password);var sql=connection.createStatement()){sql.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");}}
    }
}

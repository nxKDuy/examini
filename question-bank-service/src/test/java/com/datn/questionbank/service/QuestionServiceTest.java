package com.datn.questionbank.service;

import com.datn.questionbank.dto.QuestionDto.*;
import com.datn.questionbank.entity.*;
import com.datn.questionbank.repository.*;
import com.datn.questionbank.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;
import static com.datn.questionbank.entity.Question.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class QuestionServiceTest {
    QuestionRepository repo;
    QuestionVersionRepository versions;
    QuestionReviewRepository reviews;
    CategoryService categories;
    QuestionService service;
    ExamEligibilityService eligibility;
    ObjectMapper json;
    QuestionActor owner = new QuestionActor(UUID.randomUUID(), "TEACHER", false);
    QuestionActor admin = new QuestionActor(UUID.randomUUID(), "ADMIN", false);
    Category category = Category.builder().id(UUID.randomUUID()).name("Algebra").build();
    Question question;

    @BeforeEach void setup() {
        repo = mock(QuestionRepository.class); versions = mock(QuestionVersionRepository.class);
        reviews = mock(QuestionReviewRepository.class); categories = mock(CategoryService.class);
        json = new ObjectMapper().registerModule(new JavaTimeModule());
        var banks = mock(BankRegistry.class);
        UUID bankId = UUID.randomUUID();
        when(banks.defaultPersonal(any())).thenReturn(bankId);
        when(banks.get(bankId)).thenReturn(QuestionBank.builder().id(bankId).type(QuestionBank.BankType.PERSONAL).ownerUserId(owner.userId()).build());
        eligibility = mock(ExamEligibilityService.class);
        service = new QuestionService(repo, categories, versions, reviews, new QuestionPolicy(), json, banks, eligibility);
        when(categories.getOrThrow(category.getId())).thenReturn(category);
        when(repo.saveAndFlush(any())).thenAnswer(call -> {
            Question q = call.getArgument(0); if (q.getId() == null) q.setId(UUID.randomUUID());
            if (q.getCreatedAt() == null) q.setCreatedAt(LocalDateTime.now());
            q.getOptions().forEach(o -> { if (o.getId() == null) o.setId(UUID.randomUUID()); }); return q;
        });
        question = Question.builder().id(UUID.randomUUID()).category(category).createdBy(owner.userId())
                .content("2+2?").questionType(QuestionType.MULTIPLE_CHOICE).difficultyLevel(DifficultyLevel.EASY)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now()).build();
        question.getOptions().add(QuestionOption.builder().id(UUID.randomUUID()).question(question).content("4").correct(true).displayOrder((short)0).build());
        question.getOptions().add(QuestionOption.builder().id(UUID.randomUUID()).question(question).content("5").correct(false).displayOrder((short)1).build());
        when(repo.findForUpdate(question.getId())).thenReturn(Optional.of(question));
        when(repo.findById(question.getId())).thenReturn(Optional.of(question));
        when(repo.findAllById(any())).thenReturn(List.of(question));
        when(versions.findCurrentSnapshots(any())).thenAnswer(call -> List.of(QuestionVersion.builder()
                .questionId(question.getId()).versionNumber(question.getQuestionVersion()).snapshot(json.writeValueAsString(
                        QuestionResponse.builder().id(question.getId()).questionVersion(question.getQuestionVersion())
                        .content(question.getContent()).status(QuestionStatus.DRAFT).sourceExcerpt(question.getSourceExcerpt())
                        .expectedAnswer(question.getExpectedAnswer()).options(question.getOptions().stream()
                                .map(o -> new OptionResponse(o.getId(),o.getContent(),o.isCorrect(),o.getDisplayOrder())).toList()).build())).build()));
    }
    CreateRequest createRequest(QuestionType type, String expected, List<OptionRequest> options) {
        return new CreateRequest(category.getId(), "Question?", type, DifficultyLevel.EASY, "Legacy source", options,
                expected, QuestionScope.PERSONAL, SourceType.USER_DOCUMENT, "doc:123", "Notes", "https://example.com", "Excerpt");
    }
    UpdateRequest updateRequest(String content, long revision) {
        return new UpdateRequest(null, content, null, null, null, null, null, null, null, null, null, null, revision);
    }
    @Test void manualCreationIsDraftWithMetadataAndSnapshot() {
        var result = service.create(createRequest(QuestionType.MULTIPLE_CHOICE, null,
                List.of(new OptionRequest("Yes", true, (short)0), new OptionRequest("No", false, (short)1))), owner);
        assertEquals(QuestionStatus.DRAFT, result.status()); assertEquals(CreationMethod.MANUAL, result.creationMethod());
        assertEquals(SourceType.USER_DOCUMENT, result.sourceType()); assertEquals("doc:123", result.sourceRefId());
        assertEquals(1, result.questionVersion()); verify(versions).save(argThat(v -> v.getSnapshot().contains("Question?") && v.getChangedBy().equals(owner.userId())));
    }
    @ParameterizedTest @EnumSource(value=QuestionType.class, names={"SHORT_ANSWER","ESSAY"})
    void writtenQuestionsUseExpectedAnswerWithoutOptions(QuestionType type) {
        var result = service.create(createRequest(type, "Expected answer", List.of()), owner);
        assertTrue(result.options().isEmpty()); assertEquals("Expected answer", result.expectedAnswer());
        if (type == QuestionType.SHORT_ANSWER) assertThrows(ResponseStatusException.class, () -> service.create(createRequest(type, "", List.of()), owner));
        else assertDoesNotThrow(() -> service.create(createRequest(type, "", List.of()), owner));
    }
    @Test void ownerActivatesWithoutReviewAndLegacyReviewRoutesAreGone() {
        assertEquals(QuestionStatus.ACTIVE, service.activate(question.getId(), 1, owner).status());
        assertThrows(ResponseStatusException.class, () -> service.activate(question.getId(), 1, owner));
        assertEquals(410, assertThrows(ResponseStatusException.class,
                () -> service.submit(question.getId(), 1, owner)).getStatusCode().value());
        assertEquals(410, assertThrows(ResponseStatusException.class,
                () -> service.review(question.getId(), new ReviewRequest(QuestionReview.Decision.APPROVED, "", 1L), admin)).getStatusCode().value());
        verify(reviews, never()).save(any());
    }
    @Test void activeEditCreatesDraftRevisionWithoutChangingPreviousSnapshot() throws Exception {
        question.setStatus(QuestionStatus.ACTIVE);
        String previous = json.writeValueAsString(QuestionResponse.builder().id(question.getId()).content("2+2?").options(List.of()).build());
        var result = service.update(question.getId(), updateRequest("2+3?", 1), owner);
        assertEquals(2, result.questionVersion()); assertEquals(QuestionStatus.DRAFT, result.status());
        verify(versions).save(argThat(v -> v.getVersionNumber() == 2 && v.getSnapshot().contains("2+3?")));
        assertTrue(previous.contains("2+2?")); verify(versions, never()).delete(any());
        assertThrows(ResponseStatusException.class, () -> service.update(question.getId(), updateRequest("stale", 1), owner));
    }
    @Test void activeEditCreatesNewDraftRevisionRequiringOwnerActivation() {
        question.setStatus(QuestionStatus.ACTIVE);
        var edited=service.update(question.getId(), updateRequest("Fixed", 1), owner);
        assertEquals(QuestionStatus.DRAFT,edited.status());
        assertEquals(QuestionStatus.ACTIVE,service.activate(question.getId(),2,owner).status());
    }
    @Test void unauthorizedMutationsAndReviewRejected() {
        var other = new QuestionActor(UUID.randomUUID(), "TEACHER", false);
        assertThrows(ResponseStatusException.class, () -> service.update(question.getId(), updateRequest("Hijack", 1), other));
        assertThrows(ResponseStatusException.class, () -> service.archive(question.getId(), 1, other));
        assertThrows(ResponseStatusException.class, () -> service.activate(question.getId(), 1, other));
    }
    @Test void eligibleBatchDelegatesToSingleBankAwarePolicy() {
        when(eligibility.batch(anyList(),eq(false),eq(owner))).thenReturn(List.of());
        assertTrue(service.getByIds(List.of(question.getId()),true,false,owner).isEmpty());
        verify(eligibility).batch(List.of(question.getId()),false,owner);
    }
    @Test void approvedBatchAndSafeDtoNeverLeakAnswerKey() throws Exception {
        question.setStatus(QuestionStatus.ACTIVE); question.setExpectedAnswer("secret"); question.setSourceExcerpt("hidden source");
        var safeProjection=service.findById(question.getId(),false,owner);
        when(eligibility.batch(anyList(),eq(false),eq(owner))).thenReturn(List.of(safeProjection));
        var safe = service.getByIds(List.of(question.getId()), true, false, owner).getFirst();
        String wire = json.writeValueAsString(safe);
        assertFalse(wire.contains("correct")); assertFalse(wire.contains("expectedAnswer")); assertFalse(wire.contains("hidden source"));
        var answerProjection=service.findById(question.getId(),true,owner);
        when(eligibility.batch(anyList(),eq(true),eq(owner))).thenReturn(List.of(answerProjection));
        assertTrue(((QuestionResponse)service.getByIds(List.of(question.getId()), true, true, owner).getFirst()).options().getFirst().correct());
        var student = new QuestionActor(owner.userId(), "STUDENT", false);
        when(eligibility.batch(anyList(),eq(true),eq(student))).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN));
        assertThrows(ResponseStatusException.class, () -> service.getByIds(List.of(question.getId()), true, true, student));
        assertFalse(json.writeValueAsString(service.findById(question.getId(), false, student)).contains("correct"));
    }
    @Test void csvImportDraftMetadataAndUsefulErrors() {
        String csv = "content,type,difficulty,source,option1,correct1,option2,correct2\nGood,MULTIPLE_CHOICE,EASY,Book,Yes,true,No,false\nBad,MULTIPLE_CHOICE,EASY,,Yes,maybe,No,false\nWrong,BOGUS,EASY,,Yes,true,No,false\n";
        var result = service.importCsv(new MockMultipartFile("file", "q.csv", "text/csv", csv.getBytes()), category.getId(), owner);
        assertEquals(3, result.totalRows()); assertEquals(1, result.imported()); assertEquals(2, result.failed());
        assertTrue(result.errors().getFirst().startsWith("Dòng 3"));
        verify(repo).saveAndFlush(argThat(q -> q.getStatus() == QuestionStatus.DRAFT && q.getCreationMethod() == CreationMethod.IMPORT && q.getSourceLabel().equals("Book")));
    }
    @Test void malformedCsvOptionsAndHeadersRejected() {
        var badHeader = new MockMultipartFile("file", "content,type,difficulty,option1\nA,TRUE_FALSE,EASY,Yes\n".getBytes());
        assertThrows(ResponseStatusException.class, () -> service.importCsv(badHeader, category.getId(), owner));
        String bad = "content,type,difficulty,option1,correct1,option2,correct2\nBad,TRUE_FALSE,EASY,Yes,true,No,true\n";
        assertEquals(1, service.importCsv(new MockMultipartFile("file", bad.getBytes()), category.getId(), owner).failed());
        verify(repo, never()).saveAndFlush(any());
    }
    @Test void archiveExcludesEligibilityWithoutDeletingHistory() {
        question.setStatus(QuestionStatus.ACTIVE); service.archive(question.getId(), 1, owner);
        assertEquals(QuestionStatus.ARCHIVED, question.getStatus()); verify(repo, never()).deleteById(any());
        verify(eligibility,never()).batch(anyList(),anyBoolean(),any());
    }
    @Test void randomValidatesCountAndPassesCriteriaAndOwner() {
        when(eligibility.random(any(),any(),anyInt(),any(),isNull(),eq(owner))).thenReturn(List.of(question.getId()));
        assertEquals(List.of(question.getId()), service.getRandomQuestionIds(category.getId(), DifficultyLevel.EASY, 10, QuestionType.TRUE_FALSE, owner));
        verify(eligibility).random(category.getId(),DifficultyLevel.EASY,10,QuestionType.TRUE_FALSE,null,owner);
    }
    @Test void existingCsvTemplateRemainsCompatibleWithoutAutoApproval() throws Exception {
        try (var input = getClass().getResourceAsStream("/csv-template/questions-import-template.csv")) {
            assertNotNull(input);
            var result = service.importCsv(new MockMultipartFile("file", input.readAllBytes()), category.getId(), owner);
            assertEquals(5, result.imported()); assertEquals(0, result.failed());
            verify(repo, times(5)).saveAndFlush(argThat(q -> q.getStatus() == QuestionStatus.DRAFT && q.getCreationMethod() == CreationMethod.IMPORT));
        }
    }

}

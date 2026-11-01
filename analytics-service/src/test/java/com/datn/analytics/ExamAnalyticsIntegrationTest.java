package com.datn.analytics;

import com.datn.analytics.client.ExamClient;
import com.datn.analytics.ingest.GradingResultEvent;
import com.datn.analytics.ingest.GradingResultListener;
import com.datn.analytics.ingest.ResultProjector;
import com.datn.analytics.query.ExamAnalyticsQueries;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import feign.Request;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.datn.analytics.AnalyticsFixtures.*;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Runs against real PostgreSQL with the Flyway schema; the broker and Exam service are not needed. */
@SpringBootTest(properties = {
        "spring.datasource.url=${ANALYTICS_TEST_DATABASE_URL:jdbc:postgresql://localhost:5432/analytics_test}",
        "spring.datasource.username=${ANALYTICS_TEST_DATABASE_USER:postgres}",
        "spring.datasource.password=${ANALYTICS_TEST_DATABASE_PASSWORD:}",
        "eureka.client.enabled=false", "spring.cloud.discovery.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "jwt.secret=" + ExamAnalyticsIntegrationTest.SECRET, "analytics.exam-permission-token=test-permission"})
@AutoConfigureMockMvc
class ExamAnalyticsIntegrationTest {
    static final String SECRET = "test-only-jwt-key-32-bytes-or-longer-never-production";

    @Autowired ResultProjector projector;
    @Autowired ExamAnalyticsQueries queries;
    @Autowired GradingResultListener listener;
    @Autowired MessageConverter converter;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired DataSource dataSource;
    @Autowired MockMvc http;
    @MockBean ExamClient exams;

    UUID exam = UUID.randomUUID(), q1 = UUID.randomUUID(), q2 = UUID.randomUUID();

    @BeforeEach void clean() { db.update("TRUNCATE exam_result_projection CASCADE"); }

    static String jwt(UUID user, String role) {
        return "Bearer " + Jwts.builder().subject(user.toString()).claim("type", "access").claim("role", role)
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
    }

    GradingResultEvent graded(UUID candidate, String total, String a1, String a2) {
        return result(exam, UUID.randomUUID(), UUID.randomUUID(), candidate, 1, "GRADED", total, "10",
                List.of(item(q1, 0, "4", a1 != null, false, a1), item(q2, 1, "6", a2 != null, false, a2)));
    }

    @Test void discriminationFlagsAWrongKeyAndInsightsExplainItWithoutAi() throws Exception {
        UUID q3 = UUID.randomUUID();
        for (int i = 0; i < 12; i++) {
            boolean strong = i < 6;
            projector.apply(result(exam, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "GRADED", strong ? "14" : "6", "20",
                    List.of(item(q1, 0, "4", true, false, strong ? "4" : "0"), item(q2, 1, "6", true, false, strong ? "0" : "6"),
                            item(q3, 2, "10", true, false, strong ? "10" : "0"))));
        }
        var qs = queries.questions(exam);
        assertEquals(0, BigDecimal.ONE.compareTo(qs.get(0).discrimination()));
        assertEquals(0, BigDecimal.ONE.negate().compareTo(qs.get(1).discrimination()));
        when(exams.permission(eq(exam), any(), eq(true), eq("test-permission"))).thenReturn("OWNER");
        UUID teacher = UUID.randomUUID();
        http.perform(get("/analytics/exams/" + exam + "/insights").header("Authorization", jwt(teacher, "TEACHER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("RULES")).andExpect(jsonPath("$.level").value("AVERAGE"))
                .andExpect(jsonPath("$.aiAvailable").value(false))
                .andExpect(jsonPath("$.flags[?(@.position == 2 && @.issue == 'NEGATIVE_DISCRIMINATION')]").exists())
                .andExpect(jsonPath("$.concerns").value(hasItem(containsString("Câu 2: học sinh giỏi"))))
                .andExpect(jsonPath("$.actions").value(hasItem(containsString("Kiểm tra lại đáp án"))));
        // ai=true without a configured model falls back to the rule reading
        http.perform(get("/analytics/exams/" + exam + "/insights?ai=true&lang=en").header("Authorization", jwt(teacher, "TEACHER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("RULES")).andExpect(jsonPath("$.summary").value(containsString("12 submissions")));
    }

    @Test void adminOverviewIsForSystemAdministratorsOnly() throws Exception {
        for (int i = 0; i < 3; i++) projector.apply(graded(UUID.randomUUID(), i == 0 ? "10" : "4", "4", i == 0 ? "6" : "0"));
        String admin = "Bearer " + Jwts.builder().subject(UUID.randomUUID().toString()).claim("type", "access").claim("role", "ADMIN").claim("systemPrivilege", "ADMIN")
                .expiration(new Date(System.currentTimeMillis() + 60000)).signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        http.perform(get("/analytics/admin/overview?days=365").header("Authorization", admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.submissions").value(3)).andExpect(jsonPath("$.graded").value(3)).andExpect(jsonPath("$.perDay.length()").value(365));
        http.perform(get("/analytics/admin/overview").header("Authorization", jwt(UUID.randomUUID(), "TEACHER"))).andExpect(status().isForbidden());
        http.perform(get("/analytics/admin/overview?days=0").header("Authorization", admin)).andExpect(status().isBadRequest());
    }

    @Test void zeroDataExamReturnsEmptyAggregates() {
        var s = queries.summary(exam);
        assertEquals(0, s.submissions());
        assertEquals(0, s.candidates());
        assertNull(s.averageScore());
        assertNull(s.maxPossibleScore());
        assertEquals(10, s.scoreDistribution().size());
        assertTrue(s.scoreDistribution().stream().allMatch(b -> b.count() == 0));
        assertTrue(queries.questions(exam).isEmpty());
        assertEquals(0, queries.results(exam, null, 0, 20).totalElements());
    }

    @Test void examSummaryAndQuestionAnalyticsAggregateGradedResults() {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        projector.apply(graded(alice, "10", "4", "6"));   // 100%
        projector.apply(graded(bob, "4", "4", "0"));      // 40%
        projector.apply(graded(bob, "0", null, null));    // second attempt, unanswered: 0%
        projector.apply(result(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), alice, 1, "GRADED", "1", "1",
                List.of(item(UUID.randomUUID(), 0, "1", true, false, "1"))));  // other exam: excluded

        var s = queries.summary(exam);
        assertEquals(2, s.candidates());
        assertEquals(3, s.submissions());
        assertEquals(3, s.graded());
        assertEquals(0, s.needsReview());
        assertEquals(0, new BigDecimal("4.6667").compareTo(s.averageScore()));
        assertEquals(0, BigDecimal.ZERO.compareTo(s.minScore()));
        assertEquals(0, BigDecimal.TEN.compareTo(s.maxScore()));
        assertEquals(0, BigDecimal.TEN.compareTo(s.maxPossibleScore()));
        assertEquals(0, new BigDecimal("46.6667").compareTo(s.averagePercent()));
        long[] counts = s.scoreDistribution().stream().mapToLong(b -> b.count()).toArray();
        assertArrayEquals(new long[]{1, 0, 0, 0, 1, 0, 0, 0, 0, 1}, counts);  // 0%, 40%, 100% (last bucket inclusive)

        var questions = queries.questions(exam);
        assertEquals(List.of(q1, q2), questions.stream().map(q -> q.questionId()).toList());
        var first = questions.get(0);
        assertEquals(1, first.questionVersion());
        assertEquals(3, first.attempts());
        assertEquals(2, first.answered());
        assertEquals(2, first.correct());
        assertEquals(1, first.unanswered());
        assertEquals(0, new BigDecimal("0.6667").compareTo(first.correctRate()));
        assertEquals(0, new BigDecimal("2.6667").compareTo(first.averageAwardedPoints()));
        var second = questions.get(1);
        assertEquals(1, second.correct());
        assertEquals(1, second.incorrect());
        assertEquals(1, second.unanswered());
        assertEquals(0, new BigDecimal("0.3333").compareTo(second.correctRate()));
    }

    @Test void questionStatsStayBoundToSnapshotVersion() {
        projector.apply(graded(UUID.randomUUID(), "10", "4", "6"));
        var v2 = result(exam, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "GRADED", "0", "10",
                List.of(new GradingResultEvent.QuestionItem(q1, 2, "MULTIPLE_CHOICE", 0, n("4"), true, false, n("0")),
                        item(q2, 1, "6", true, false, "0")));
        projector.apply(v2);
        var rows = queries.questions(exam).stream().filter(q -> q.questionId().equals(q1)).toList();
        assertEquals(List.of(1L, 2L), rows.stream().map(q -> q.questionVersion()).toList());
        assertEquals(1, rows.get(0).correct());
        assertEquals(1, rows.get(1).incorrect());
    }

    @Test void partiallyGradedExamSeparatesNeedsReview() {
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        projector.apply(graded(a, "8", "4", "4"));  // partial credit on q2
        projector.apply(result(exam, UUID.randomUUID(), UUID.randomUUID(), b, 1, "NEEDS_REVIEW", "4", "10",
                List.of(item(q1, 0, "4", true, false, "4"), item(q2, 1, "6", true, true, null))));

        var s = queries.summary(exam);
        assertEquals(2, s.submissions());
        assertEquals(1, s.graded());
        assertEquals(1, s.needsReview());
        // Provisional NEEDS_REVIEW totals never enter score statistics.
        assertEquals(0, new BigDecimal("8").compareTo(s.averageScore()));
        assertEquals(0, new BigDecimal("8").compareTo(s.minScore()));
        assertEquals(1, s.scoreDistribution().stream().mapToLong(x -> x.count()).sum());

        var q = queries.questions(exam).get(1);
        assertEquals(2, q.attempts());
        assertEquals(1, q.partial());
        assertEquals(1, q.pendingReview());
        assertEquals(0, q.correct());
        assertEquals(0, BigDecimal.ZERO.compareTo(q.correctRate()));  // 0 correct of 1 graded item
        assertEquals(0, new BigDecimal("4").compareTo(q.averageAwardedPoints()));
        // Objective item in the NEEDS_REVIEW attempt is still graded and counted.
        assertEquals(2, queries.questions(exam).get(0).correct());

        var pending = queries.results(exam, "NEEDS_REVIEW", 0, 20);
        assertEquals(1, pending.totalElements());
        assertNull(pending.content().get(0).score());
        assertNull(pending.content().get(0).gradedAt());
    }

    @Test void duplicateStaleAndConcurrentEventsAreIdempotent() throws Exception {
        UUID session = UUID.randomUUID(), resultId = UUID.randomUUID(), candidate = UUID.randomUUID();
        var review = result(exam, session, resultId, candidate, 1, "NEEDS_REVIEW", "4", "10",
                List.of(item(q1, 0, "4", true, false, "4"), item(q2, 1, "6", true, true, null)));
        var manual = result(exam, session, resultId, candidate, 2, "GRADED", "9", "10",
                List.of(item(q1, 0, "4", true, false, "4"), item(q2, 1, "6", true, false, "5")));

        var pool = Executors.newFixedThreadPool(4);
        try {
            List<Callable<Boolean>> deliveries = Collections.nCopies(4, () -> projector.apply(review));
            long applied = 0;
            for (var f : pool.invokeAll(deliveries)) if (f.get()) applied++;
            assertEquals(1, applied);
        } finally { pool.shutdownNow(); }

        assertTrue(projector.apply(manual));
        assertFalse(projector.apply(manual));   // redelivery
        assertFalse(projector.apply(review));   // late, older revision
        var s = queries.summary(exam);
        assertEquals(1, s.submissions());
        assertEquals(1, s.graded());
        assertEquals(0, s.needsReview());
        assertEquals(0, new BigDecimal("9").compareTo(s.averageScore()));
        assertEquals(1, queries.questions(exam).get(1).partial());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM question_outcome_projection", Integer.class));
        assertEquals(2L, db.queryForObject("SELECT result_revision FROM exam_result_projection", Long.class));
    }

    @Test void identityConflictForSameSessionIsRejected() {
        var first = graded(UUID.randomUUID(), "10", "4", "6");
        projector.apply(first);
        var hijack = result(UUID.randomUUID(), first.sessionId(), first.resultId(), first.candidateId(), 2, "GRADED", "0", "10", first.questions());
        assertThrows(ResultProjector.InvalidEventException.class, () -> projector.apply(hijack));
        assertEquals(1, queries.summary(exam).submissions());
    }

    @Test void wireContractDeserializesAndInvalidEventsDeadLetter() {
        UUID session = UUID.randomUUID();
        String body = """
                {"eventId":"%s","schemaVersion":1,"occurredAt":"2026-10-05T09:00:00Z","resultId":"%s","resultRevision":1,
                 "sessionId":"%s","examId":"%s","candidateId":"%s","attemptNumber":2,"status":"GRADED",
                 "totalScore":4,"maxScore":10,"submittedAt":"2026-10-05T08:59:00Z","gradedAt":"2026-10-05T09:00:00Z",
                 "questions":[{"questionId":"%s","questionVersion":3,"questionType":"MULTIPLE_CHOICE","position":0,
                   "pointValue":4,"answered":true,"needsReview":false,"awardedPoints":4},
                  {"questionId":"%s","questionVersion":1,"questionType":"ESSAY","position":1,
                   "pointValue":6,"answered":false,"needsReview":false,"awardedPoints":null}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), session, exam, UUID.randomUUID(), q1, q2);
        var props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setInferredArgumentType(GradingResultEvent.class);
        var event = (GradingResultEvent) converter.fromMessage(new Message(body.getBytes(StandardCharsets.UTF_8), props));
        listener.onResultRecorded(event);
        var row = queries.results(exam, null, 0, 20).content().get(0);
        assertEquals(session, row.sessionId());
        assertEquals(2, row.attemptNumber());
        assertEquals("2026-10-05T09:00:00Z", row.gradedAt().toString());
        assertEquals(3, queries.questions(exam).get(0).questionVersion());

        var invalid = result(exam, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "GRADED", null, "10", List.of());
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> listener.onResultRecorded(invalid));
    }

    @Test void resultsArePagedNewestFirst() throws Exception {
        for (int i = 1; i <= 3; i++)
            projector.apply(result(exam, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), i, "GRADED", "5", "10",
                    List.of(item(q1, 0, "10", true, false, "5"))));
        var page = queries.results(exam, "GRADED", 0, 2);
        assertEquals(3, page.totalElements());
        assertEquals(2, page.totalPages());
        assertEquals(2, page.content().size());
        assertTrue(page.content().get(0).submittedAt().isAfter(page.content().get(1).submittedAt()));
        assertEquals(1, queries.results(exam, "GRADED", 1, 2).content().size());
        assertEquals(0, queries.results(exam, "PENDING", 0, 2).totalElements());
    }

    /**
     * MAIN review regression: two conflicting FIRST deliveries for one session. The test holds the
     * per-session lock, proves both deliveries are queued on it (before either identity check),
     * then releases them. Whatever order PostgreSQL grants, exactly one identity wins and the
     * result row and its question rows stay consistent; the loser is rejected as a contract violation.
     */
    @Test void concurrentConflictingFirstDeliveriesKeepOneConsistentIdentity() throws Exception {
        UUID session = UUID.randomUUID(), otherExam = UUID.randomUUID();
        var a = result(exam, session, UUID.randomUUID(), UUID.randomUUID(), 1, "GRADED", "10", "10",
                List.of(item(q1, 0, "4", true, false, "4"), item(q2, 1, "6", true, false, "6")));
        // Higher revision: before the fix it could overwrite A's scores under A's identity and
        // write its question rows under otherExam.
        var b = result(otherExam, session, UUID.randomUUID(), UUID.randomUUID(), 2, "GRADED", "0", "10",
                List.of(item(q1, 0, "4", true, false, "0"), item(q2, 1, "6", true, false, "0")));
        var pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> fa, fb;
            try (Connection gate = dataSource.getConnection()) {
                gate.setAutoCommit(true);
                try (var lock = gate.prepareStatement("SELECT pg_advisory_lock(hashtextextended(CAST(? AS text), 0))")) {
                    lock.setString(1, session.toString());
                    lock.execute();
                }
                fa = pool.submit(() -> projector.apply(a));
                fb = pool.submit(() -> projector.apply(b));
                long deadline = System.currentTimeMillis() + 15000;
                while (db.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND NOT granted", Long.class) < 2) {
                    assertTrue(System.currentTimeMillis() < deadline, "both deliveries must wait on the session lock");
                    Thread.sleep(20);
                }
                assertEquals(0, db.queryForObject("SELECT count(*) FROM exam_result_projection", Integer.class));
                try (var unlock = gate.prepareStatement("SELECT pg_advisory_unlock(hashtextextended(CAST(? AS text), 0))")) {
                    unlock.setString(1, session.toString());
                    unlock.execute();
                }
            }
            GradingResultEvent winner = null;
            int rejected = 0;
            for (var pair : List.of(Map.entry(fa, a), Map.entry(fb, b))) {
                try {
                    assertTrue(pair.getKey().get(30, TimeUnit.SECONDS));
                    winner = pair.getValue();
                } catch (ExecutionException failure) {
                    assertInstanceOf(ResultProjector.InvalidEventException.class, failure.getCause());
                    rejected++;
                }
            }
            assertNotNull(winner);
            assertEquals(1, rejected);

            var row = db.queryForMap("SELECT result_id, exam_id, candidate_id, total_score, result_revision FROM exam_result_projection");
            assertEquals(winner.resultId(), row.get("result_id"));
            assertEquals(winner.examId(), row.get("exam_id"));
            assertEquals(winner.candidateId(), row.get("candidate_id"));
            assertEquals(0, winner.totalScore().compareTo((BigDecimal) row.get("total_score")));
            assertEquals(winner.resultRevision(), row.get("result_revision"));
            assertEquals(2, db.queryForObject("SELECT count(*) FROM question_outcome_projection WHERE exam_id = ?", Integer.class, winner.examId()));
            assertEquals(0, db.queryForObject("""
                    SELECT count(*) FROM question_outcome_projection o JOIN exam_result_projection r USING (session_id)
                    WHERE o.exam_id <> r.exam_id""", Integer.class));
            UUID loserExam = winner == a ? otherExam : exam;
            assertEquals(0, queries.summary(loserExam).submissions());
            assertTrue(queries.questions(loserExam).isEmpty());
        } finally { pool.shutdownNow(); }

        // Duplicate/stale semantics are unchanged after the race.
        var row = db.queryForMap("SELECT result_id, exam_id, candidate_id, result_revision FROM exam_result_projection");
        var same = result((UUID) row.get("exam_id"), session, (UUID) row.get("result_id"), (UUID) row.get("candidate_id"),
                (Long) row.get("result_revision"), "GRADED", "5", "10", List.of(item(q1, 0, "4", true, false, "4")));
        assertFalse(projector.apply(same));
    }

    Map<String, Object> validWireEvent() throws Exception {
        return json.readValue("""
                {"eventId":"%s","schemaVersion":1,"occurredAt":"2026-10-05T09:00:00Z","resultId":"%s","resultRevision":1,
                 "sessionId":"%s","examId":"%s","candidateId":"%s","attemptNumber":1,"status":"GRADED",
                 "totalScore":4,"maxScore":4,"submittedAt":"2026-10-05T08:59:00Z","gradedAt":"2026-10-05T09:00:00Z",
                 "questions":[{"questionId":"%s","questionVersion":3,"questionType":"MULTIPLE_CHOICE","position":0,
                   "pointValue":4,"answered":true,"needsReview":false,"awardedPoints":4}]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), exam, UUID.randomUUID(), q1),
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> firstQuestion(Map<String, Object> event) {
        return ((List<Map<String, Object>>) event.get("questions")).get(0);
    }

    GradingResultEvent convert(Map<String, Object> event) throws Exception {
        var props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setInferredArgumentType(GradingResultEvent.class);
        return (GradingResultEvent) converter.fromMessage(new Message(json.writeValueAsBytes(event), props));
    }

    @Test void malformedEnvelopesAreRejectedNotCoerced() throws Exception {
        assertDoesNotThrow(() -> convert(validWireEvent()));
        List<java.util.function.Consumer<Map<String, Object>>> malformed = List.of(
                e -> firstQuestion(e).put("questionVersion", 1.5),
                e -> e.put("resultRevision", 2.5),
                e -> e.put("schemaVersion", 1.5),
                e -> e.put("resultRevision", null),
                e -> firstQuestion(e).remove("answered"),
                e -> firstQuestion(e).put("needsReview", null),
                e -> e.put("occurredAt", "yesterday"),
                e -> e.put("occurredAt", "2026-10-05T09:00:00"));  // no offset: not an instant
        for (var corrupt : malformed) {
            var event = validWireEvent();
            corrupt.accept(event);
            assertThrows(MessageConversionException.class, () -> convert(event), event.toString());
        }
        // Absent occurredAt parses to null and is rejected by contract validation (dead-lettered).
        var missing = validWireEvent();
        missing.remove("occurredAt");
        var parsed = convert(missing);
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> listener.onResultRecorded(parsed));
        assertEquals(0, queries.summary(exam).submissions());
    }

    static FeignException forbidden() {
        var req = Request.create(Request.HttpMethod.GET, "/exams", Map.of(), null, StandardCharsets.UTF_8, null);
        return new FeignException.Forbidden("denied", req, null, null);
    }

    @Test void authorizationFollowsExamMembership() throws Exception {
        UUID owner = UUID.randomUUID(), manager = UUID.randomUUID(), proctor = UUID.randomUUID(),
                unrelated = UUID.randomUUID(), student = UUID.randomUUID(), admin = UUID.randomUUID();
        projector.apply(graded(student, "10", "4", "6"));
        when(exams.permission(exam, owner, true, "test-permission")).thenReturn("OWNER");
        when(exams.permission(exam, manager, true, "test-permission")).thenReturn("MANAGER");
        // Exam's manage permission denies PROCTOR, unrelated teachers and ADMIN without membership.
        for (UUID denied : List.of(proctor, unrelated, admin))
            when(exams.permission(exam, denied, true, "test-permission")).thenThrow(forbidden());

        for (UUID allowed : List.of(owner, manager)) {
            http.perform(get("/analytics/exams/{id}/summary", exam).header("Authorization", jwt(allowed, "TEACHER")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.submissions").value(1));
            http.perform(get("/analytics/exams/{id}/questions", exam).header("Authorization", jwt(allowed, "TEACHER")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$", hasSize(2)));
            http.perform(get("/analytics/exams/{id}/results", exam).header("Authorization", jwt(allowed, "TEACHER")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].candidateId").value(student.toString()));
            http.perform(get("/analytics/exams/{id}/students", exam).header("Authorization", jwt(allowed, "TEACHER")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].candidateId").value(student.toString()));
        }
        for (String path : List.of("summary", "questions", "results", "students")) {
            http.perform(get("/analytics/exams/{id}/" + path, exam).header("Authorization", jwt(proctor, "TEACHER")))
                    .andExpect(status().isForbidden());
            http.perform(get("/analytics/exams/{id}/" + path, exam).header("Authorization", jwt(unrelated, "TEACHER")))
                    .andExpect(status().isForbidden());
            http.perform(get("/analytics/exams/{id}/" + path, exam).header("Authorization", jwt(admin, "ADMIN")))
                    .andExpect(status().isForbidden());
            // Even a candidate of this exam gets no aggregate/participant data.
            http.perform(get("/analytics/exams/{id}/" + path, exam).header("Authorization", jwt(student, "STUDENT")))
                    .andExpect(status().isForbidden());
            http.perform(get("/analytics/exams/{id}/" + path, exam)).andExpect(status().isUnauthorized());
            // Client-supplied identity headers or service tokens are not identity.
            http.perform(get("/analytics/exams/{id}/" + path, exam).header("X-User-Id", owner.toString()))
                    .andExpect(status().isUnauthorized());
            http.perform(get("/analytics/exams/{id}/" + path, exam).header("Authorization", jwt(owner, "TEACHER"))
                    .header("X-Service-Token", "test-permission")).andExpect(status().isUnauthorized());
        }
        verify(exams, never()).permission(any(), eq(student), anyBoolean(), any());
        verify(exams, never()).permission(any(), any(), eq(false), any());

        var refreshToken = "Bearer " + Jwts.builder().subject(owner.toString()).claim("type", "refresh").claim("role", "TEACHER")
                .expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8))).compact();
        http.perform(get("/analytics/exams/{id}/summary", exam).header("Authorization", refreshToken))
                .andExpect(status().isUnauthorized());
        http.perform(get("/analytics/exams/{id}/results", exam).param("status", "BOGUS")
                .header("Authorization", jwt(owner, "TEACHER"))).andExpect(status().isBadRequest());
        http.perform(get("/analytics/exams/{id}/results", exam).param("candidateId", "bad-id")
                .header("Authorization", jwt(owner, "TEACHER"))).andExpect(status().isBadRequest());

        UUID missing = UUID.randomUUID();
        var req = Request.create(Request.HttpMethod.GET, "/exams", Map.of(), null, StandardCharsets.UTF_8, null);
        when(exams.permission(missing, owner, true, "test-permission")).thenThrow(new FeignException.NotFound("missing", req, null, null));
        http.perform(get("/analytics/exams/{id}/summary", missing).header("Authorization", jwt(owner, "TEACHER")))
                .andExpect(status().isNotFound());
    }
}

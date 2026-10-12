package com.datn.analytics;

import com.datn.analytics.ingest.ExamEventsListener;
import com.datn.analytics.ingest.ResultProjector;
import com.datn.analytics.query.ExamAnalyticsQueries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.Executors;

import static com.datn.analytics.AnalyticsFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL + Flyway; synthetic examini.events messages (not a producer E2E). */
@SpringBootTest(properties = {
        "spring.datasource.url=${ANALYTICS_TEST_DATABASE_URL:jdbc:postgresql://localhost:5432/analytics_test}",
        "spring.datasource.username=${ANALYTICS_TEST_DATABASE_USER:postgres}",
        "spring.datasource.password=${ANALYTICS_TEST_DATABASE_PASSWORD:}",
        "eureka.client.enabled=false", "spring.cloud.discovery.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "jwt.secret=test-only-jwt-key-32-bytes-or-longer-never-production",
        "analytics.exam-permission-token=test-permission"})
class ParticipationEventsIntegrationTest {
    @Autowired ResultProjector results;
    @Autowired ExamEventsListener listener;
    @Autowired ExamAnalyticsQueries queries;
    @Autowired JdbcTemplate db;
    @MockBean com.datn.analytics.client.ExamClient exams;

    UUID exam = UUID.randomUUID(), q1 = UUID.randomUUID();

    @BeforeEach void clean() {
        db.update("TRUNCATE exam_result_projection, exam_participation_projection, attempt_invalidation, processed_event CASCADE");
    }

    String participant(UUID eventId, UUID examId, String cause, long revision, int registered, int eligible, int revoked) {
        return """
                {"eventId":"%s","eventType":"exam.participant.changed","eventVersion":1,"occurredAt":"2026-10-08T09:00:00Z",
                 "payload":{"examId":"%s","examTitle":"Midterm","userId":"%s","cause":"%s","actorUserId":"%s",
                  "recipientRevision":%d,"registeredCount":%d,"eligibleCount":%d,"revokedCount":%d}}
                """.formatted(eventId, examId, UUID.randomUUID(), cause, UUID.randomUUID(), revision, registered, eligible, revoked);
    }

    String invalidated(UUID eventId, UUID session, UUID examId, UUID user, int attempt, String status) {
        return """
                {"eventId":"%s","eventType":"exam.attempt.invalidated","eventVersion":1,"occurredAt":"2026-10-08T09:30:00Z",
                 "payload":{"sessionId":"%s","examId":"%s","userId":"%s","attemptNumber":%d,"status":"%s",
                  "reason":"proctor decision","actor":"%s","invalidatedAt":"2026-10-08T09:29:00Z"}}
                """.formatted(eventId, session, examId, user, attempt, status, UUID.randomUUID());
    }

    void deliver(String body) {
        var props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        listener.onEvent(new Message(body.getBytes(StandardCharsets.UTF_8), props));
    }

    void graded(UUID session, UUID candidate, String total) {
        results.apply(result(exam, session, UUID.randomUUID(), candidate, 1, "GRADED", total, "10",
                List.of(item(q1, 0, "10", true, false, total))));
    }

    @Test void noParticipationProjectionYieldsNullsNotZeros() {
        graded(UUID.randomUUID(), UUID.randomUUID(), "8");
        var s = queries.summary(exam);
        assertNull(s.registeredCount());
        assertNull(s.eligibleCount());
        assertNull(s.submissionRate());
        assertEquals(0, s.invalidatedCount());
        assertEquals(1, s.submissions());
    }

    @Test void participantSnapshotUsesHighestRevisionAndDeduplicatesEvents() {
        UUID e1 = UUID.randomUUID();
        deliver(participant(e1, exam, "ASSIGNED", 1, 1, 1, 0));
        deliver(participant(UUID.randomUUID(), exam, "ASSIGNED", 3, 3, 3, 0));
        deliver(participant(UUID.randomUUID(), exam, "REVOKED", 4, 3, 2, 1));
        deliver(participant(UUID.randomUUID(), exam, "ASSIGNED", 2, 2, 2, 0));   // late, older revision
        deliver(participant(e1, exam, "ASSIGNED", 1, 1, 1, 0));                   // redelivery
        var s = queries.summary(exam);
        assertEquals(3, s.registeredCount());
        assertEquals(2, s.eligibleCount());
        assertEquals(4L, db.queryForObject("SELECT recipient_revision FROM exam_participation_projection", Long.class));
        assertEquals(4, db.queryForObject("SELECT count(*) FROM processed_event", Integer.class));
    }

    @Test void attemptGrantedNeverChangesCounts() {
        deliver(participant(UUID.randomUUID(), exam, "ASSIGNED", 2, 2, 2, 0));
        deliver(participant(UUID.randomUUID(), exam, "ATTEMPT_GRANTED", 99, 50, 50, 0));
        assertEquals(2, queries.summary(exam).eligibleCount());
        assertEquals(2L, db.queryForObject("SELECT recipient_revision FROM exam_participation_projection", Long.class));
        // A grant arriving first leaves the projection absent (null), never a fabricated snapshot.
        UUID other = UUID.randomUUID();
        deliver(participant(UUID.randomUUID(), other, "ATTEMPT_GRANTED", 1, 1, 1, 0));
        assertNull(queries.summary(other).eligibleCount());
    }

    @Test void submissionRateExcludesInvalidatedAndIsOrderIndependent() {
        deliver(participant(UUID.randomUUID(), exam, "ASSIGNED", 4, 4, 4, 0));
        UUID s1 = UUID.randomUUID(), s2 = UUID.randomUUID(), s3 = UUID.randomUUID();
        UUID c1 = UUID.randomUUID(), c2 = UUID.randomUUID(), c3 = UUID.randomUUID();
        graded(s1, c1, "10");
        graded(s2, c2, "2");
        // s3: invalidation arrives BEFORE its result; s2: AFTER.
        deliver(invalidated(UUID.randomUUID(), s3, exam, c3, 1, "SUBMITTED"));
        graded(s3, c3, "9");
        deliver(invalidated(UUID.randomUUID(), s2, exam, c2, 1, "AUTO_SUBMITTED"));

        var s = queries.summary(exam);
        assertEquals(1, s.submissions());
        assertEquals(1, s.candidates());
        assertEquals(2, s.invalidatedCount());
        assertEquals(0, new BigDecimal("10").compareTo(s.averageScore()));
        assertEquals(0, new BigDecimal("0.2500").compareTo(s.submissionRate()));   // 1 of 4 eligible
        assertEquals(0, new BigDecimal("1.0000").compareTo(s.gradingCompletionRate())); // 1 graded of 1 valid submission
        assertNull(s.kpi().participatedStudents());
        assertNull(s.kpi().publishedAverageScore());
        assertEquals(1, s.kpi().submittedStudents());
        assertEquals(0, new BigDecimal("0.2500").compareTo(s.kpi().completionRate())); // 1 / 4 eligible
        assertEquals(4, s.kpi().eligibleCount());
        assertEquals(0, s.kpi().needsGradingCount());
        assertEquals(2, s.kpi().unavailable().size());
        assertEquals(1, s.scoreDistribution().stream().mapToLong(b -> b.count()).sum());
        assertEquals(1, queries.questions(exam).get(0).attempts());
        // Trace is kept: all three results stay listed, invalidated ones flagged.
        var rows = queries.results(exam, null, 0, 20).content();
        assertEquals(3, rows.size());
        assertEquals(Set.of(s2, s3), rows.stream().filter(r -> r.invalidated()).map(r -> r.sessionId())
                .collect(java.util.stream.Collectors.toSet()));
    }

    @Test void completionRateUsesEligibleDenominatorAndIsClamped() {
        // 4 registered, 2 revoked: the denominator is the 2 still-eligible Students, not 4.
        deliver(participant(UUID.randomUUID(), exam, "REVOKED", 1, 4, 2, 2));
        graded(UUID.randomUUID(), UUID.randomUUID(), "8");
        var k = queries.summary(exam).kpi();
        assertEquals(4, k.registeredCount());
        assertEquals(2, k.eligibleCount());
        assertEquals(0, new BigDecimal("0.5000").compareTo(k.completionRate()));
        assertTrue(k.completionRateDenominator().startsWith("eligibleCount"));
        assertFalse(k.unavailable().containsKey("completionRate"));
        // A revoked Student's earlier submission can push submitters above eligible: KPI clamps, legacy rate does not.
        graded(UUID.randomUUID(), UUID.randomUUID(), "6");
        graded(UUID.randomUUID(), UUID.randomUUID(), "4");
        var s = queries.summary(exam);
        assertEquals(0, new BigDecimal("1.0000").compareTo(s.kpi().completionRate()));
        assertEquals(0, new BigDecimal("1.5000").compareTo(s.submissionRate()));
        // Nobody eligible: null with a reason, never 0.
        deliver(participant(UUID.randomUUID(), exam, "REVOKED", 2, 4, 0, 4));
        k = queries.summary(exam).kpi();
        assertNull(k.completionRate());
        assertTrue(k.unavailable().containsKey("completionRate"));
    }

    com.datn.analytics.ingest.GradingResultEvent withAttempt(com.datn.analytics.ingest.GradingResultEvent e, UUID eventId, Integer attempt) {
        return new com.datn.analytics.ingest.GradingResultEvent(eventId, e.schemaVersion(), e.occurredAt(), e.resultId(),
                e.resultRevision(), e.sessionId(), e.examId(), e.candidateId(), attempt, e.status(), e.totalScore(),
                e.maxScore(), e.submittedAt(), e.gradedAt(), e.questions());
    }

    /** Regression for the 2 historical analytics.grading-result.dlq messages (attemptNumber 0, NEEDS_REVIEW, rev 1). */
    @Test void legacyZeroAttemptNumberIsUnknownNotDeadLettered() {
        UUID session = UUID.randomUUID(), result = UUID.randomUUID(), candidate = UUID.randomUUID(), event = UUID.randomUUID();
        var items = List.of(item(q1, 0, "2", true, false, "2"), item(UUID.randomUUID(), 1, "5", true, true, null));
        var corrected = withAttempt(result(exam, session, result, candidate, 1, "NEEDS_REVIEW", "2", "7", items), event, 1);
        assertTrue(results.apply(corrected));
        // Replaying the dead-lettered copy (same eventId and revision, attemptNumber 0) is an accepted no-op.
        assertFalse(results.apply(withAttempt(corrected, event, 0)));
        assertEquals(1, db.queryForObject("SELECT attempt_number FROM exam_result_projection WHERE session_id = ?", Integer.class, session));
        // A newer revision still carrying 0 updates the result but never erases the known attempt number.
        var graded = result(exam, session, result, candidate, 2, "GRADED", "6", "7",
                List.of(item(q1, 0, "2", true, false, "2"), item(UUID.randomUUID(), 1, "5", true, false, "4")));
        assertTrue(results.apply(withAttempt(graded, UUID.randomUUID(), 0)));
        assertEquals(1, db.queryForObject("SELECT attempt_number FROM exam_result_projection WHERE session_id = ?", Integer.class, session));
        assertEquals("GRADED", db.queryForObject("SELECT status FROM exam_result_projection WHERE session_id = ?", String.class, session));
        // First sight with 0: projected with an unknown (null) attempt number.
        UUID other = UUID.randomUUID();
        assertTrue(results.apply(withAttempt(result(exam, other, UUID.randomUUID(), UUID.randomUUID(), 1, "NEEDS_REVIEW", "2", "7", items),
                UUID.randomUUID(), 0)));
        assertNull(db.queryForObject("SELECT attempt_number FROM exam_result_projection WHERE session_id = ?", Integer.class, other));
        assertThrows(ResultProjector.InvalidEventException.class,
                () -> results.apply(withAttempt(result(exam, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "PENDING", null, "7", items),
                        UUID.randomUUID(), -1)));
    }

    @Test void studentSummariesAndResultFiltersCountValidAttemptsPrecisely() {
        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
        UUID a1 = UUID.randomUUID(), a2 = UUID.randomUUID(), b1 = UUID.randomUUID();
        graded(a1, alice, "7");
        results.apply(result(exam, a2, UUID.randomUUID(), alice, 2, "GRADED", "9", "10",
                List.of(item(q1, 0, "10", true, false, "9"))));
        graded(b1, bob, "5");
        deliver(invalidated(UUID.randomUUID(), a1, exam, alice, 1, "SUBMITTED"));

        var students = queries.students(exam, 0, 20);
        assertEquals(2, students.totalElements());
        assertEquals(2, students.content().size());
        var a = students.content().stream().filter(s -> s.candidateId().equals(alice)).findFirst().orElseThrow();
        assertEquals(1, a.submittedAttempts());
        assertEquals(1, a.gradedAttempts());
        assertEquals(a2, a.latestSessionId());
        assertEquals("GRADED", a.latestGradingStatus());

        assertEquals(2, queries.results(exam, "GRADED", null, false, 0, 20).totalElements());
        assertEquals(1, queries.results(exam, null, alice, true, 0, 20).totalElements());
        assertEquals(a1, queries.results(exam, null, alice, true, 0, 20).content().get(0).sessionId());
        assertEquals(1, queries.students(exam, 0, 1).content().size());
        assertEquals(2, queries.students(exam, 0, 1).totalPages());
    }

    @Test void invalidationWithoutResultAndDuplicatesAreCountedOnce() {
        UUID session = UUID.randomUUID(), user = UUID.randomUUID(), event = UUID.randomUUID();
        deliver(invalidated(event, session, exam, user, 1, "CANCELLED"));
        deliver(invalidated(event, session, exam, user, 1, "CANCELLED"));                    // same eventId
        deliver(invalidated(UUID.randomUUID(), session, exam, user, 1, "CANCELLED"));         // new eventId, same fact
        var s = queries.summary(exam);
        assertEquals(1, s.invalidatedCount());
        assertEquals(0, s.submissions());
        assertNull(s.submissionRate());
        assertNull(s.gradingCompletionRate());
        assertNull(s.kpi().completionRate());
        // Same session claimed by another exam is a contract violation.
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> deliver(invalidated(UUID.randomUUID(), session, UUID.randomUUID(), user, 1, "CANCELLED")));
        assertEquals(1, queries.summary(exam).invalidatedCount());
    }

    @Test void concurrentDuplicateDeliveriesAreSafe() throws Exception {
        UUID event = UUID.randomUUID(), session = UUID.randomUUID(), user = UUID.randomUUID();
        String body = invalidated(event, session, exam, user, 1, "SUBMITTED");
        String assigned = participant(UUID.randomUUID(), exam, "ASSIGNED", 1, 1, 1, 0);
        var pool = Executors.newFixedThreadPool(6);
        try {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 3; i++) {
                futures.add(pool.submit(() -> deliver(body)));
                futures.add(pool.submit(() -> deliver(assigned)));
            }
            for (var f : futures) f.get();
        } finally { pool.shutdownNow(); }
        assertEquals(1, queries.summary(exam).invalidatedCount());
        assertEquals(1, queries.summary(exam).eligibleCount());
        assertEquals(2, db.queryForObject("SELECT count(*) FROM processed_event", Integer.class));
    }

    @Test void malformedEnvelopesGoToDeadLetterAndConsumeNothing() {
        String valid = participant(UUID.randomUUID(), exam, "ASSIGNED", 1, 1, 1, 0);
        String inv = invalidated(UUID.randomUUID(), UUID.randomUUID(), exam, UUID.randomUUID(), 1, "SUBMITTED");
        List<String> bad = List.of(
                "not json", "[]", "{}",
                valid.replace("\"eventVersion\":1", "\"eventVersion\":1.0"),
                valid.replace("\"eventVersion\":1", "\"eventVersion\":\"1\""),
                valid.replace("\"eventVersion\":1", "\"eventVersion\":2"),
                valid.replace("2026-10-08T09:00:00Z", "yesterday"),
                valid.replace("2026-10-08T09:00:00Z", "2026-10-08T09:00:00"),
                valid.replace("\"recipientRevision\":1", "\"recipientRevision\":1.5"),
                valid.replace("\"registeredCount\":1", "\"registeredCount\":-1"),
                valid.replace("\"registeredCount\":1", "\"registeredCount\":5"),   // != eligible + revoked
                valid.replace("\"cause\":\"ASSIGNED\"", "\"cause\":\"TELEPORTED\""),
                valid.replace("exam.participant.changed", "exam.unknown"),
                valid.replaceFirst("\"eventId\":\"[^\"]+\"", "\"eventId\":\"nope\""),
                valid.replaceFirst("\"examId\":\"[^\"]+\",", ""),
                inv.replace("\"attemptNumber\":1", "\"attemptNumber\":0"),
                inv.replace("\"status\":\"SUBMITTED\"", "\"status\":\"IN_PROGRESS\""),
                inv.replace("\"reason\":\"proctor decision\",", ""),
                inv.replace("2026-10-08T09:29:00Z", "later"));
        for (String body : bad)
            assertThrows(AmqpRejectAndDontRequeueException.class, () -> deliver(body), body);
        // Routing key must match the declared type.
        var props = new MessageProperties();
        props.setReceivedRoutingKey("exam.attempt.invalidated");
        assertThrows(AmqpRejectAndDontRequeueException.class,
                () -> listener.onEvent(new Message(valid.getBytes(StandardCharsets.UTF_8), props)));
        assertEquals(0, db.queryForObject("SELECT count(*) FROM processed_event", Integer.class));
        assertDoesNotThrow(() -> deliver(valid));
    }
}

package com.datn.analytics;

import com.datn.analytics.ingest.ExamEventsListener;
import com.datn.analytics.ingest.PublishedResultListener;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Real PostgreSQL + Flyway; synthetic exam.attempt.started / grading.result.published v1 messages. */
@SpringBootTest(properties = {
        "spring.datasource.url=${ANALYTICS_TEST_DATABASE_URL:jdbc:postgresql://localhost:5432/analytics_test}",
        "spring.datasource.username=${ANALYTICS_TEST_DATABASE_USER:postgres}",
        "spring.datasource.password=${ANALYTICS_TEST_DATABASE_PASSWORD:}",
        "eureka.client.enabled=false", "spring.cloud.discovery.enabled=false",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "jwt.secret=test-only-jwt-key-32-bytes-or-longer-never-production",
        "analytics.exam-permission-token=test-permission"})
class StartedAndPublishedEventsIntegrationTest {
    @Autowired ExamEventsListener examEvents;
    @Autowired PublishedResultListener publishedEvents;
    @Autowired ExamAnalyticsQueries queries;
    @Autowired JdbcTemplate db;
    @MockBean com.datn.analytics.client.ExamClient exams;

    UUID exam = UUID.randomUUID();

    @BeforeEach void clean() {
        db.update("""
                TRUNCATE exam_result_projection, exam_participation_projection, attempt_invalidation, processed_event,
                    attempt_started, published_result_projection CASCADE""");
    }

    static Message message(String body, String routingKey) {
        var props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setReceivedRoutingKey(routingKey);
        return new Message(body.getBytes(StandardCharsets.UTF_8), props);
    }

    void started(UUID eventId, UUID session, UUID candidate, int attempt) {
        examEvents.onEvent(message("""
                {"eventId":"%s","eventType":"exam.attempt.started","eventVersion":1,"occurredAt":"2026-10-12T08:00:00Z",
                 "payload":{"sessionId":"%s","examId":"%s","candidateId":"%s","attemptNumber":%d,
                  "startedAt":"2026-10-12T08:00:00Z","expiresAt":"2026-10-12T09:00:00Z"}}
                """.formatted(eventId, session, exam, candidate, attempt), "exam.attempt.started"));
    }

    void invalidated(UUID session, UUID candidate) {
        examEvents.onEvent(message("""
                {"eventId":"%s","eventType":"exam.attempt.invalidated","eventVersion":1,"occurredAt":"2026-10-12T10:00:00Z",
                 "payload":{"sessionId":"%s","examId":"%s","userId":"%s","attemptNumber":1,"status":"SUBMITTED",
                  "reason":"proctor decision","actor":"%s","invalidatedAt":"2026-10-12T10:00:00Z"}}
                """.formatted(UUID.randomUUID(), session, exam, candidate, UUID.randomUUID()), "exam.attempt.invalidated"));
    }

    String publishedBody(UUID eventId, UUID result, long revision, UUID session, UUID candidate, String total,
                         String releasedAt, boolean invalidated) {
        return """
                {"eventId":"%s","eventType":"grading.result.published","eventVersion":1,"occurredAt":"%s",
                 "payload":{"publicationId":"%s","resultId":"%s","resultRevision":%d,"sessionId":"%s","examId":"%s",
                  "candidateId":"%s","attemptNumber":1,"regraded":%s,"totalScore":%s,"maxScore":10.0,"gradingMode":"AUTO",
                  "gradedAt":"2026-10-12T09:00:00Z","releasedAt":"%s","invalidated":%s,
                  "questions":[{"questionId":"%s","questionVersion":1,"questionType":"SINGLE_CHOICE","position":0,
                   "pointValue":10.0,"answered":true,"awardedPoints":%s}]}}
                """.formatted(eventId, releasedAt, UUID.randomUUID(), result, revision, session, exam, candidate,
                revision > 1, total, releasedAt, invalidated, UUID.randomUUID(), total);
    }

    void published(UUID result, long revision, UUID session, UUID candidate, String total, String releasedAt, boolean invalidated) {
        publishedEvents.onEvent(message(publishedBody(UUID.randomUUID(), result, revision, session, candidate, total, releasedAt, invalidated),
                "grading.result.published.v1"));
    }

    @Test void participatedStudentsCountsDistinctValidStartsIdempotently() {
        assertNull(queries.summary(exam).kpi().participatedStudents());
        assertTrue(queries.summary(exam).kpi().unavailable().containsKey("participatedStudents"));

        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(), carol = UUID.randomUUID();
        UUID a1 = UUID.randomUUID(), a2 = UUID.randomUUID(), b1 = UUID.randomUUID(), c1 = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        started(event, a1, alice, 1);
        started(event, a1, alice, 1);                        // redelivery
        started(UUID.randomUUID(), a1, alice, 1);            // replay/backfill with another eventId
        started(UUID.randomUUID(), a2, alice, 2);            // second attempt: same Student
        invalidated(c1, carol);                              // invalidation BEFORE the start
        started(UUID.randomUUID(), c1, carol, 1);
        started(UUID.randomUUID(), b1, bob, 1);
        invalidated(b1, bob);                                // invalidation AFTER the start

        var k = queries.summary(exam).kpi();
        assertEquals(1L, k.participatedStudents());
        assertFalse(k.unavailable().containsKey("participatedStudents"));
        assertEquals(4, db.queryForObject("SELECT count(*) FROM attempt_started", Integer.class));

        // Same session, different candidate: contract violation, dead-lettered, nothing changes.
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> started(UUID.randomUUID(), a1, bob, 1));
        assertEquals(1L, queries.summary(exam).kpi().participatedStudents());
    }

    @Test void allStartsInvalidatedIsZeroNotNull() {
        UUID s = UUID.randomUUID(), u = UUID.randomUUID();
        started(UUID.randomUUID(), s, u, 1);
        invalidated(s, u);
        assertEquals(0L, queries.summary(exam).kpi().participatedStudents());
    }

    @Test void publishedStatsUseLatestReleasedValidAttemptPerStudent() {
        var none = queries.summary(exam);
        assertNull(none.kpi().publishedAverageScore());
        assertEquals(0, none.published().students());
        assertTrue(none.kpi().unavailable().containsKey("publishedAverageScore"));

        UUID alice = UUID.randomUUID(), bob = UUID.randomUUID(), carol = UUID.randomUUID(), dave = UUID.randomUUID();
        UUID a1 = UUID.randomUUID(), a2 = UUID.randomUUID(), b1 = UUID.randomUUID(), c1 = UUID.randomUUID(),
                c2 = UUID.randomUUID(), d1 = UUID.randomUUID();
        UUID ra2 = UUID.randomUUID();
        // Alice: attempt 1 released at 4, attempt 2 released later; regrade rev 3 arrives BEFORE rev 2.
        published(UUID.randomUUID(), 1, a1, alice, "4", "2026-10-12T10:00:00Z", false);
        published(ra2, 3, a2, alice, "8", "2026-10-12T12:00:00Z", false);
        published(ra2, 2, a2, alice, "6", "2026-10-12T11:00:00Z", false);   // stale, ignored
        // Bob: one release; Carol: latest attempt invalidated -> falls back to her earlier release.
        published(UUID.randomUUID(), 1, b1, bob, "10", "2026-10-12T10:00:00Z", false);
        published(UUID.randomUUID(), 1, c1, carol, "2", "2026-10-12T10:00:00Z", false);
        published(UUID.randomUUID(), 1, c2, carol, "9", "2026-10-12T12:00:00Z", false);
        invalidated(c2, carol);
        // Dave: release already flagged invalidated by Grading (backfill) -> excluded.
        published(UUID.randomUUID(), 1, d1, dave, "7", "2026-10-12T10:00:00Z", true);

        var s = queries.summary(exam);
        var p = s.published();
        assertEquals(3, p.students());                                         // alice 8, bob 10, carol 2
        assertEquals(0, new BigDecimal("6.6667").compareTo(p.averageScore()));
        assertEquals(0, new BigDecimal("10").compareTo(p.highScore()));
        assertEquals(0, new BigDecimal("2").compareTo(p.lowScore()));
        assertEquals(0, new BigDecimal("66.6667").compareTo(p.averagePercent()));
        assertEquals(0, new BigDecimal("100").compareTo(p.highPercent()));
        assertEquals(0, new BigDecimal("20").compareTo(p.lowPercent()));
        assertEquals(1, p.distribution().get(2).count());                      // 20%
        assertEquals(1, p.distribution().get(8).count());                      // 80%
        assertEquals(1, p.distribution().get(9).count());                      // 100% in the last bucket
        assertEquals(3, p.distribution().stream().mapToLong(b -> b.count()).sum());
        assertEquals(0, new BigDecimal("66.6667").compareTo(s.kpi().publishedAverageScore()));
        assertFalse(s.kpi().unavailable().containsKey("publishedAverageScore"));
        assertEquals(3L, db.queryForObject("SELECT result_revision FROM published_result_projection WHERE session_id = ?", Long.class, a2));
    }

    @Test void publishedDeliveryIsIdempotentAndStrict() {
        UUID event = UUID.randomUUID(), result = UUID.randomUUID(), session = UUID.randomUUID(), u = UUID.randomUUID();
        String body = publishedBody(event, result, 1, session, u, "5", "2026-10-12T10:00:00Z", false);
        publishedEvents.onEvent(message(body, "grading.result.published.v1"));
        publishedEvents.onEvent(message(body, "grading.result.published.v1"));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM processed_event", Integer.class));
        assertEquals(1, queries.summary(exam).published().students());

        // Routing key must be eventType + ".v1"; wrong version and conflicting identity are dead-lettered.
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> publishedEvents.onEvent(message(
                publishedBody(UUID.randomUUID(), result, 2, session, u, "6", "2026-10-12T11:00:00Z", false), "grading.result.published")));
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> publishedEvents.onEvent(message(
                publishedBody(UUID.randomUUID(), result, 2, session, u, "6", "2026-10-12T11:00:00Z", false)
                        .replace("\"eventVersion\":1", "\"eventVersion\":2"), "grading.result.published.v1")));
        assertThrows(AmqpRejectAndDontRequeueException.class, () -> published(result, 2, session, UUID.randomUUID(), "6",
                "2026-10-12T11:00:00Z", false));
        assertEquals(0, new BigDecimal("5").compareTo(queries.summary(exam).published().highScore()));
        assertEquals(1, db.queryForObject("SELECT count(*) FROM processed_event", Integer.class));
    }
}

package com.datn.analytics.ingest;

import com.datn.analytics.ingest.ResultProjector.Outcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static com.datn.analytics.AnalyticsFixtures.*;
import static org.junit.jupiter.api.Assertions.*;

/** Pure rules; no database or broker. */
class ResultProjectorRulesTest {
    @Test void outcomeDerivesFromGradingAwardedPoints() {
        UUID q = UUID.randomUUID();
        assertEquals(Outcome.CORRECT, ResultProjector.outcome(item(q, 0, "2", true, false, "2")));
        assertEquals(Outcome.PARTIAL, ResultProjector.outcome(item(q, 0, "2", true, false, "0.5")));
        assertEquals(Outcome.INCORRECT, ResultProjector.outcome(item(q, 0, "2", true, false, "0")));
        assertEquals(Outcome.UNANSWERED, ResultProjector.outcome(item(q, 0, "2", false, false, "0")));
        assertEquals(Outcome.UNANSWERED, ResultProjector.outcome(item(q, 0, "2", false, false, null)));
        assertEquals(Outcome.PENDING_REVIEW, ResultProjector.outcome(item(q, 0, "2", true, true, null)));
    }

    @Test void contractViolationsAreRejected() {
        UUID exam = UUID.randomUUID(), q = UUID.randomUUID();
        var ok = List.of(item(q, 0, "2", true, false, "2"));
        assertDoesNotThrow(() -> ResultProjector.validate(result(exam, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "GRADED", "2", "2", ok)));
        List<GradingResultEvent> bad = List.of(
                result(exam, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "GRADED", null, "2", ok),
                result(exam, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "DONE", "2", "2", ok),
                result(exam, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 0, "GRADED", "2", "2", ok),
                result(exam, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "GRADED", "3", "2", ok),
                result(exam, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "GRADED", "2", "2",
                        List.of(item(q, 0, "2", true, false, "2"), item(q, 1, "2", true, false, "0"))),
                result(exam, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "GRADED", "2", "2",
                        List.of(item(q, 0, "2", true, false, "5"))),
                result(exam, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "GRADED", "2", "2",
                        List.of(item(q, 0, "2", true, false, null))));
        for (var e : bad) assertThrows(ResultProjector.InvalidEventException.class, () -> ResultProjector.validate(e));
        var base = result(exam, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 1, "GRADED", "2", "2", ok);
        var v2 = new GradingResultEvent(base.eventId(), 2, base.occurredAt(), base.resultId(), 1,
                base.sessionId(), exam, base.candidateId(), 1, "GRADED", n("2"), n("2"), null, null, ok);
        assertThrows(ResultProjector.InvalidEventException.class, () -> ResultProjector.validate(v2));
    }
}

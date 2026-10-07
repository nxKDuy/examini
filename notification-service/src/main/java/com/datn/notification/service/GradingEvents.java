package com.datn.notification.service;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.UUID;

/**
 * Strict v1 payloads of the manual-grading notification events (contracts/manual-grading-scope.md). The payloads carry
 * identifiers and the Exam title only: no score, answer, key, feedback, audit reason or token. Unknown extra fields are
 * ignored and never stored.
 */
public final class GradingEvents {
    private GradingEvents(){}

    /** First publication of a result. resultId/resultRevision are additive: both absent (older producers) or both valid. */
    public record Released(UUID sessionId,UUID examId,String examTitle,UUID studentUserId,String gradingMode,UUID resultId,Long resultRevision){}
    /** A later publication of a result that was already released. Never sent for drafts. */
    public record Regraded(UUID sessionId,UUID examId,String examTitle,UUID studentUserId,long resultRevision){}
    /** A submitted result needs a teacher (e.g. essay AI unavailable). Recipients are resolved from the Exam, not the event. */
    public record ReviewRequired(UUID resultId,UUID sessionId,UUID examId,String examTitle,long resultRevision){}

    public static Released released(JsonNode p){
        object(p);
        boolean hasId=p.has("resultId"),hasRevision=p.has("resultRevision");
        if(hasId!=hasRevision)throw new IllegalArgumentException("resultId and resultRevision go together");
        return new Released(EventCatalog.uuid(p,"sessionId"),EventCatalog.uuid(p,"examId"),EventCatalog.title(p),EventCatalog.uuid(p,"studentUserId"),
            EventCatalog.oneOf(p,"gradingMode","AUTO","MANUAL"),hasId?EventCatalog.uuid(p,"resultId"):null,hasRevision?EventCatalog.positive(p,"resultRevision"):null);
    }
    public static Regraded regraded(JsonNode p){
        object(p);
        return new Regraded(EventCatalog.uuid(p,"sessionId"),EventCatalog.uuid(p,"examId"),EventCatalog.title(p),EventCatalog.uuid(p,"studentUserId"),EventCatalog.positive(p,"resultRevision"));
    }
    public static ReviewRequired reviewRequired(JsonNode p){
        object(p);
        return new ReviewRequired(EventCatalog.uuid(p,"resultId"),EventCatalog.uuid(p,"sessionId"),EventCatalog.uuid(p,"examId"),EventCatalog.title(p),EventCatalog.positive(p,"resultRevision"));
    }
    private static void object(JsonNode p){if(p==null || !p.isObject())throw new IllegalArgumentException("payload must be a JSON object");}
}

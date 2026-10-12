package com.datn.examsession.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public final class IntegrityDto {
    private IntegrityDto() {}
    public enum EventType { TAB_SWITCH, WINDOW_BLUR, EXIT_FULLSCREEN, COPY, PASTE,
        NETWORK_DISCONNECT, NETWORK_RECONNECT, FULLSCREEN_ENTER, WINDOW_FOCUS,
        PAGE_RELOAD, SESSION_RESUME }
    public enum Severity { LOW, MEDIUM }
    /** Why an accepted signal did not consume one warning-budget unit. */
    public enum NotCountedReason { NOT_BUDGET_SIGNAL, COALESCED, STALE }
    public enum VisibilityState { HIDDEN, VISIBLE }
    public enum NetworkState { OFFLINE, ONLINE }
    public record Metadata(Long durationMs, VisibilityState visibilityState, Boolean fromFullscreen,
                           Boolean toFullscreen, NetworkState networkState) {
        @JsonAnySetter public void rejectUnknown(String key, Object ignored) {
            throw new IllegalArgumentException("Unsupported integrity metadata");
        }
    }
    public record Ingest(@NotNull UUID clientEventId, @NotNull EventType type,
                         Instant occurredAt, @Valid Metadata metadata) {
        @JsonAnySetter public void rejectUnknown(String key,Object ignored) {
            throw new IllegalArgumentException("Unsupported integrity event field");
        }
        public Ingest { if(occurredAt!=null)occurredAt=occurredAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS); }
    }
    public record Event(UUID id, UUID clientEventId, UUID examId, UUID sessionId, UUID userId,
                        EventType type, Instant occurredAt, Instant receivedAt, Severity severity,
                        Metadata metadata, String source) {}
    public record Receipt(Event event, boolean duplicate, long warningCount, int warningLimit,
                          boolean warningRecommended, String warning, boolean autoSubmitted,
                          boolean counted, NotCountedReason notCountedReason) {}
    public record Summary(UUID examId, UUID sessionId, long totalEvents, Map<EventType,Long> counts) {}
    public record Audit(UUID id,UUID examId,UUID sessionId,UUID actorUserId,String actorType,String action,
                        UUID triggerEventId,Long eventCount,Instant occurredAt,String metadata) {}
}

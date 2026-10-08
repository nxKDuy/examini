package com.datn.analytics.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/** Standard {@code examini.events} envelope v1, parsed strictly: nothing is coerced. */
public record EventEnvelope(UUID eventId, String eventType, Instant occurredAt, JsonNode payload) {
    public static final int VERSION = 1;
    public static final String PARTICIPANT_CHANGED = "exam.participant.changed";
    public static final String ATTEMPT_INVALIDATED = "exam.attempt.invalidated";
    public static final Set<String> HANDLED = Set.of(PARTICIPANT_CHANGED, ATTEMPT_INVALIDATED);

    public static EventEnvelope parse(ObjectMapper json, byte[] body, String routingKey) {
        JsonNode root;
        try {
            root = json.readTree(body);
        } catch (java.io.IOException e) {
            throw new ResultProjector.InvalidEventException("Malformed JSON");
        }
        if (root == null || !root.isObject()) throw new ResultProjector.InvalidEventException("Envelope must be an object");
        UUID eventId = uuid(root, "eventId");
        String type = text(root, "eventType");
        if (!HANDLED.contains(type)) throw new ResultProjector.InvalidEventException("Unsupported eventType " + type);
        if (routingKey != null && !routingKey.isEmpty() && !routingKey.equals(type))
            throw new ResultProjector.InvalidEventException("Routing key does not match eventType");
        int version = integer(root, "eventVersion");
        if (version != VERSION) throw new ResultProjector.InvalidEventException("Unsupported eventVersion " + version);
        Instant occurredAt;
        try {
            occurredAt = Instant.parse(text(root, "occurredAt"));
        } catch (java.time.format.DateTimeParseException e) {
            throw new ResultProjector.InvalidEventException("occurredAt must be an ISO-8601 instant");
        }
        JsonNode payload = root.get("payload");
        if (payload == null || !payload.isObject()) throw new ResultProjector.InvalidEventException("payload must be an object");
        return new EventEnvelope(eventId, type, occurredAt, payload);
    }

    // ---- strict field readers (also used for payloads) ----

    public static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual() || v.asText().isBlank()) throw new ResultProjector.InvalidEventException(field + " must be a non-empty string");
        return v.asText();
    }

    public static UUID uuid(JsonNode node, String field) {
        try {
            return UUID.fromString(text(node, field));
        } catch (IllegalArgumentException e) {
            if (e instanceof ResultProjector.InvalidEventException) throw e;
            throw new ResultProjector.InvalidEventException(field + " must be a UUID");
        }
    }

    /** JSON integer only: 1.5, 1.0 and "1" are rejected. */
    public static int integer(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToInt()) throw new ResultProjector.InvalidEventException(field + " must be a JSON integer");
        return v.intValue();
    }

    public static long longInteger(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isIntegralNumber() || !v.canConvertToLong()) throw new ResultProjector.InvalidEventException(field + " must be a JSON integer");
        return v.longValue();
    }

    public static Instant instant(JsonNode node, String field) {
        try {
            return Instant.parse(text(node, field));
        } catch (java.time.format.DateTimeParseException e) {
            throw new ResultProjector.InvalidEventException(field + " must be an ISO-8601 instant");
        }
    }

    public static void present(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || v.isNull()) throw new ResultProjector.InvalidEventException(field + " is required");
    }
}

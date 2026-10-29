package com.datn.examsession.dto;

import com.datn.examsession.entity.CameraEvent;
import com.datn.examsession.entity.CameraIdentityCheck;
import java.time.Instant;
import java.util.*;

/** Camera proctoring API (contracts/camera-proctoring.md). Snapshots and video are separate binary routes. */
public final class CameraDto {
    private CameraDto() {}
    /** Browser report: what was detected and the numbers behind it (faces, distance, yaw, pitch, seconds). */
    public record Report(UUID clientEventId, CameraEvent.Type type, Instant occurredAt, Map<String, Object> detail) {}
    public record Event(UUID id, UUID sessionId, UUID userId, CameraEvent.Type type, String reason, Instant occurredAt,
                        Instant receivedAt, Map<String, Object> detail, boolean hasSnapshot) {}
    public record Receipt(Event event, boolean duplicate) {}
    public record Recording(String mimeType, int chunks, long bytes, Instant startedAt, Instant lastChunkAt) {}
    public record ChunkReceipt(int seq, boolean stored, int chunks, long bytes) {}
    /** Retention of the media: days kept after the deadline (0 = forever), when they go, when they went. */
    public record Retention(int days, Instant deleteAt, Instant purgedAt) {}
    /** Enrollment descriptor (128 numbers from the browser's face recognition model). */
    public record IdentityRequest(List<Double> descriptor) {}
    /** Face verification at exam entry against the student's profile. */
    public record Identity(CameraIdentityCheck.Outcome outcome, Double distance, double threshold, int attempts, int maxAttempts,
                           boolean mayContinue, Instant checkedAt, Instant profileCreatedAt) {}
    /** Staff view of one attempt; {@code profile} tells whether the student's profile photo can be fetched. */
    public record SessionCamera(UUID sessionId, UUID userId, Event reference, Recording recording, List<Event> events, Retention retention,
                                Identity identity, boolean profile) {}
    /** Staff overview per attempt of an exam. */
    public record SessionSummary(UUID sessionId, UUID userId, Map<CameraEvent.Type, Long> counts, long violations,
                                 Instant lastEventAt, Recording recording, Retention retention, CameraIdentityCheck.Outcome identity) {}

    /** Reason shown to proctors, in Vietnamese (the UI language of record). */
    public static String reason(CameraEvent.Type type) {
        return switch (type) {
            case REFERENCE -> "Ảnh khuôn mặt lúc bắt đầu";
            case NO_FACE -> "Không thấy khuôn mặt trong khung hình";
            case MULTIPLE_FACES -> "Có nhiều người trong khung hình";
            case FACE_MISMATCH -> "Khuôn mặt không khớp với ảnh lúc bắt đầu";
            case LOOKING_AWAY -> "Quay mặt hoặc nhìn ra ngoài màn hình lâu";
            case CAMERA_OFF -> "Camera bị tắt hoặc mất kết nối";
            case CAMERA_ON -> "Camera hoạt động lại";
            case PROFILE_MISMATCH -> "Khuôn mặt không khớp với hồ sơ khuôn mặt của thí sinh";
        };
    }
    public static boolean violation(CameraEvent.Type type) { return type != CameraEvent.Type.REFERENCE && type != CameraEvent.Type.CAMERA_ON; }
}

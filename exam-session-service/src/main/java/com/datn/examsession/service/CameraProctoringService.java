package com.datn.examsession.service;

import com.datn.examsession.client.ExamClient;
import com.datn.examsession.dto.CameraDto.*;
import com.datn.examsession.entity.*;
import com.datn.examsession.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static com.datn.examsession.dto.CameraDto.reason;
import static com.datn.examsession.dto.CameraDto.violation;

/**
 * Camera proctoring evidence. The browser analyses frames (face count, identity distance to the enrollment photo,
 * head pose) and reports violations with a snapshot; it also uploads the compressed recording in ordered chunks.
 * Nothing here decides an outcome: proctors review the evidence and can lock the exam with the existing controls.
 */
@Service @RequiredArgsConstructor
public class CameraProctoringService {
    static final int MAX_SNAPSHOT_BYTES = 300 * 1024, MAX_CHUNK_BYTES = 4 * 1024 * 1024, MAX_SEQ = 20_000, MAX_DETAIL = 8;
    static final long MAX_RECORDING_BYTES = 600L * 1024 * 1024;
    static final Duration UPLOAD_GRACE = Duration.ofMinutes(2);
    static final Set<String> DETAIL_KEYS = Set.of("faces", "distance", "threshold", "yaw", "pitch", "seconds", "score");

    private final SessionRepository sessions;
    private final CameraEventRepository events;
    private final CameraRecordingRepository recordings;
    private final CameraStorage storage;
    private final ExamClient exams;
    private final CameraMediaPurgeRepository purges;
    private final CameraRetention retention;
    private final CameraIdentityCheckRepository identities;
    private final FaceProfileRepository profiles;
    @Value("${exam.internal-token:}") private String token;
    @Value("${exam.camera.events-per-minute:30}") private int perMinute;
    @Value("${exam.camera.max-events-per-session:2000}") private int maxEvents;

    static ResponseStatusException error(int status, String message) { return new ResponseStatusException(HttpStatus.valueOf(status), message); }
    private static Instant now() { return Instant.now().truncatedTo(ChronoUnit.MICROS); }

    // ── Student side ─────────────────────────────────────────────────────────────

    ExamSession owned(UUID sessionId, UUID actor, boolean lock) {
        var s = (lock ? sessions.lockById(sessionId) : sessions.findById(sessionId)).orElseThrow(() -> error(404, "Session not found"));
        if (!s.getUserId().equals(actor)) throw error(403, "Not your session");
        if (s.getIntegrityPolicy() == null || !s.getIntegrityPolicy().requireCamera()) throw error(409, "Camera proctoring is not enabled for this attempt");
        return s;
    }
    static boolean active(ExamSession s, Instant now) { return s.getStatus() == ExamSession.Status.IN_PROGRESS && now.isBefore(s.getExpiresAt()); }

    /** Enrollment photo and violations. Snapshot is required except for camera on/off. */
    @Transactional
    public Receipt report(UUID sessionId, UUID actor, Report input, byte[] snapshot) {
        if (input == null || input.clientEventId() == null || input.type() == null) throw error(400, "clientEventId and type required");
        if (input.type() == CameraEvent.Type.PROFILE_MISMATCH) throw error(400, "PROFILE_MISMATCH is recorded by the server");
        var s = owned(sessionId, actor, true);
        var existing = events.findBySessionIdAndClientEventId(sessionId, input.clientEventId());
        if (existing.isPresent()) {
            if (existing.get().getType() != input.type()) throw error(409, "clientEventId already used for a different event");
            return new Receipt(dto(existing.get()), true);
        }
        Instant now = now();
        if (!active(s, now)) throw error(409, "Attempt is not in progress");
        var detail = detail(input.detail());
        boolean needsImage = input.type() != CameraEvent.Type.CAMERA_OFF && input.type() != CameraEvent.Type.CAMERA_ON;
        if (needsImage && (snapshot == null || snapshot.length == 0)) throw error(400, "Snapshot required for " + input.type());
        if (snapshot != null && snapshot.length > 0) jpeg(snapshot);
        if (input.occurredAt() != null && (input.occurredAt().isBefore(now.minus(Duration.ofDays(1))) || input.occurredAt().isAfter(now.plus(Duration.ofMinutes(5)))))
            throw error(400, "occurredAt out of range");
        if (events.countBySessionId(sessionId) >= maxEvents || events.countBySessionIdAndReceivedAtAfter(sessionId, now.minusSeconds(60)) >= perMinute)
            throw error(429, "Camera event limit reached; answering is not affected");
        UUID id = UUID.randomUUID();
        String path = null;
        if (snapshot != null && snapshot.length > 0) {
            try { path = storage.saveSnapshot(s.getExamId(), sessionId, id, snapshot); }
            catch (IOException e) { throw error(503, "Snapshot storage unavailable"); }
        }
        var e = events.saveAndFlush(CameraEvent.builder().id(id).sessionId(sessionId).examId(s.getExamId()).userId(s.getUserId())
                .clientEventId(input.clientEventId()).type(input.type()).occurredAt(input.occurredAt()).receivedAt(now)
                .detail(detail).snapshotPath(path).snapshotBytes(path == null ? null : snapshot.length).build());
        return new Receipt(dto(e), false);
    }

    /** Appends chunk {@code seq} of the recording; retries of a stored seq are acknowledged without rewriting. */
    @Transactional
    public ChunkReceipt chunk(UUID sessionId, UUID actor, int seq, String contentType, byte[] data) {
        if (seq < 0 || seq > MAX_SEQ) throw error(400, "seq out of range");
        if (data == null || data.length == 0) throw error(400, "Empty chunk");
        if (data.length > MAX_CHUNK_BYTES) throw error(413, "Chunk too large");
        String mime = contentType == null ? "" : contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
        if (!mime.equals("video/webm") && !mime.equals("video/mp4")) throw error(415, "Only video/webm or video/mp4");
        var s = owned(sessionId, actor, false);
        Instant now = now();
        // The last chunks may arrive just after submit or time-out.
        Instant ended = s.getEndTime() == null ? s.getExpiresAt() : s.getEndTime().toInstant(ZoneOffset.UTC);
        if (s.getStatus() != ExamSession.Status.IN_PROGRESS && now.isAfter(ended.plus(UPLOAD_GRACE)) || now.isAfter(s.getExpiresAt().plus(UPLOAD_GRACE)))
            throw error(409, "Recording window closed");
        var rec = recordings.lockById(sessionId).orElseGet(() -> recordings.saveAndFlush(CameraRecording.builder().sessionId(sessionId)
                .examId(s.getExamId()).userId(s.getUserId()).mimeType(mime).startedAt(now).build()));
        if (!rec.getMimeType().equals(mime)) throw error(409, "Recording format changed");
        if (rec.getBytes() + data.length > MAX_RECORDING_BYTES) throw error(413, "Recording size limit reached");
        boolean stored;
        try { stored = storage.saveChunk(s.getExamId(), sessionId, seq, data); }
        catch (IOException e) { throw error(503, "Recording storage unavailable"); }
        if (stored) { rec.setChunks(rec.getChunks() + 1); rec.setBytes(rec.getBytes() + data.length); rec.setLastChunkAt(now); }
        return new ChunkReceipt(seq, stored, rec.getChunks(), rec.getBytes());
    }

    // ── Staff side ──────────────────────────────────────────────────────────────

    private void review(UUID exam, UUID actor, String role) { review(exam, actor, role, Set.of("OWNER", "MANAGER", "PROCTOR")); }
    /** @return the actor's permission on the exam, one of {@code allowed} */
    String review(UUID exam, UUID actor, String role, Set<String> allowed) {
        if (!Set.of("TEACHER", "ADMIN").contains(role)) throw error(403, "Camera review requires exam staff");
        if (token == null || token.isBlank()) throw error(503, "Exam permission service not configured");
        String permission = exams.permission(exam, actor, false, token);
        if (!allowed.contains(permission == null ? "" : permission)) throw error(403, "Exam camera review permission denied");
        return permission;
    }
    ExamSession sessionOf(UUID sessionId) { return sessions.findById(sessionId).orElseThrow(() -> error(404, "Session not found")); }
    ExamSession reviewed(UUID sessionId, UUID actor, String role) {
        var s = sessions.findById(sessionId).orElseThrow(() -> error(404, "Session not found"));
        review(s.getExamId(), actor, role);
        return s;
    }

    @Transactional(readOnly = true)
    public SessionCamera session(UUID sessionId, UUID actor, String role) {
        var s = reviewed(sessionId, actor, role);
        var all = events.findBySessionIdOrderByReceivedAtAscIdAsc(sessionId);
        var reference = all.stream().filter(e -> e.getType() == CameraEvent.Type.REFERENCE).reduce((a, b) -> b).map(this::dto).orElse(null);
        return new SessionCamera(sessionId, s.getUserId(), reference, recordings.findById(sessionId).map(CameraProctoringService::recording).orElse(null),
                all.stream().filter(e -> e.getType() != CameraEvent.Type.REFERENCE).map(this::dto).toList(),
                retention(s.getExpiresAt(), purges.findById(sessionId).map(CameraMediaPurge::getPurgedAt).orElse(null)),
                identities.findById(sessionId).map(FaceIdentityService::dto).orElse(null), profiles.existsById(s.getUserId()));
    }

    @Transactional(readOnly = true)
    public List<SessionSummary> exam(UUID examId, UUID actor, String role) {
        review(examId, actor, role);
        var bySession = new LinkedHashMap<UUID, Map<CameraEvent.Type, Long>>();
        var users = new HashMap<UUID, UUID>(); var last = new HashMap<UUID, Instant>();
        for (var c : events.countsByExam(examId)) {
            bySession.computeIfAbsent(c.getSessionId(), k -> new EnumMap<>(CameraEvent.Type.class)).put(c.getType(), c.getTotal());
            users.put(c.getSessionId(), c.getUserId());
            last.merge(c.getSessionId(), c.getLast(), (a, b) -> a.isAfter(b) ? a : b);
        }
        var recs = new HashMap<UUID, CameraRecording>();
        for (var r : recordings.findByExamId(examId)) { recs.put(r.getSessionId(), r); users.putIfAbsent(r.getSessionId(), r.getUserId()); bySession.putIfAbsent(r.getSessionId(), new EnumMap<>(CameraEvent.Type.class)); }
        var deadlines = new HashMap<UUID, Instant>();
        for (var s : sessions.findAllById(bySession.keySet())) deadlines.put(s.getId(), s.getExpiresAt());
        var outcomes = new HashMap<UUID, CameraIdentityCheck.Outcome>();
        for (var c : identities.findBySessionIdIn(bySession.keySet())) outcomes.put(c.getSessionId(), c.getOutcome());
        var purged = new HashMap<UUID, Instant>();
        for (var p : purges.findByExamId(examId)) purged.put(p.getSessionId(), p.getPurgedAt());
        return bySession.entrySet().stream().map(e -> new SessionSummary(e.getKey(), users.get(e.getKey()), e.getValue(),
                        e.getValue().entrySet().stream().filter(x -> violation(x.getKey())).mapToLong(Map.Entry::getValue).sum(),
                        last.get(e.getKey()), recs.containsKey(e.getKey()) ? recording(recs.get(e.getKey())) : null,
                        retention(deadlines.get(e.getKey()), purged.get(e.getKey())), outcomes.get(e.getKey())))
                .sorted(Comparator.comparingLong(SessionSummary::violations).reversed().thenComparing(x -> x.sessionId().toString())).toList();
    }

    @Transactional(readOnly = true)
    public byte[] snapshot(UUID sessionId, UUID eventId, UUID actor, String role) {
        reviewed(sessionId, actor, role);
        var e = events.findByIdAndSessionId(eventId, sessionId).orElseThrow(() -> error(404, "Event not found"));
        if (e.getSnapshotPath() == null) throw purges.existsById(sessionId) ? error(410, "Snapshot deleted by the retention policy") : error(404, "No snapshot");
        try { return storage.readSnapshot(e.getSnapshotPath()); } catch (IOException ex) { throw error(404, "Snapshot missing"); }
    }

    public record Video(String mimeType, long length, List<Path> files) {}
    @Transactional(readOnly = true)
    public Video video(UUID sessionId, UUID actor, String role) {
        var s = reviewed(sessionId, actor, role);
        if (purges.existsById(sessionId)) throw error(410, "Recording deleted by the retention policy");
        var rec = recordings.findById(sessionId).orElseThrow(() -> error(404, "No recording"));
        try { var files = storage.chunks(s.getExamId(), sessionId); if (files.isEmpty()) throw error(404, "No recording"); return new Video(rec.getMimeType(), storage.size(files), files); }
        catch (IOException e) { throw error(503, "Recording storage unavailable"); }
    }
    public void stream(Video v, OutputStream out) throws IOException { storage.copyChunks(v.files(), out); }

    // ── Helpers ─────────────────────────────────────────────────────────────────

    private Event dto(CameraEvent e) {
        return new Event(e.getId(), e.getSessionId(), e.getUserId(), e.getType(), reason(e.getType()), e.getOccurredAt(), e.getReceivedAt(),
                e.getDetail(), e.getSnapshotPath() != null);
    }
    private Retention retention(Instant deadline, Instant purgedAt) { return new Retention(retention.days(), retention.deleteAt(deadline), purgedAt); }
    private static Recording recording(CameraRecording r) { return new Recording(r.getMimeType(), r.getChunks(), r.getBytes(), r.getStartedAt(), r.getLastChunkAt()); }

    /** Only small numeric measurements are kept: no free text, images or identifiers from the client. */
    static Map<String, Object> detail(Map<String, Object> in) {
        if (in == null) return Map.of();
        if (in.size() > MAX_DETAIL) throw error(400, "Too many detail fields");
        var out = new LinkedHashMap<String, Object>();
        for (var e : in.entrySet()) {
            if (!DETAIL_KEYS.contains(e.getKey())) throw error(400, "Detail field not allowed: " + e.getKey());
            if (!(e.getValue() instanceof Number n) || !Double.isFinite(n.doubleValue()) || Math.abs(n.doubleValue()) > 1e6) throw error(400, "Detail values must be numbers");
            out.put(e.getKey(), n);
        }
        return out;
    }
    /** Saves an evidence event created by the server itself (e.g. PROFILE_MISMATCH). */
    CameraEvent record(ExamSession s, CameraEvent.Type type, Map<String, Object> detail, byte[] snapshot) {
        UUID id = UUID.randomUUID();
        String path = null;
        if (snapshot != null) {
            try { path = storage.saveSnapshot(s.getExamId(), s.getId(), id, snapshot); }
            catch (IOException e) { throw error(503, "Snapshot storage unavailable"); }
        }
        return events.saveAndFlush(CameraEvent.builder().id(id).sessionId(s.getId()).examId(s.getExamId()).userId(s.getUserId())
                .clientEventId(UUID.randomUUID()).type(type).occurredAt(now()).receivedAt(now()).detail(detail)
                .snapshotPath(path).snapshotBytes(path == null ? null : snapshot.length).build());
    }

    static void jpeg(byte[] data) {
        if (data.length > MAX_SNAPSHOT_BYTES) throw error(413, "Snapshot too large");
        if (data.length < 4 || (data[0] & 0xFF) != 0xFF || (data[1] & 0xFF) != 0xD8 || (data[2] & 0xFF) != 0xFF) throw error(415, "Snapshot must be JPEG");
    }
}

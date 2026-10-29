package com.datn.examsession;

import com.datn.examsession.client.ExamClient;
import com.datn.examsession.dto.IntegrityPolicy;
import com.datn.examsession.service.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:postgresql://localhost:5432/session_test","spring.datasource.username=postgres","spring.datasource.password=",
        "eureka.client.enabled=false","spring.cloud.discovery.enabled=false","jwt.secret=unit-test-only-signing-key-32-bytes-minimum",
        "exam.internal-token=test-internal","spring.rabbitmq.listener.simple.auto-startup=false","exam.camera.events-per-minute=6"})
@AutoConfigureMockMvc
class CameraProctoringTest {
    @DynamicPropertySource static void storage(DynamicPropertyRegistry r) throws Exception {
        var dir = Files.createTempDirectory("camera-test"); r.add("exam.camera.storage-dir", dir::toString);
    }
    @Autowired CameraRetention retention; @Autowired SessionService core; @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate db;
    @MockBean ExamClient exams; @MockBean SessionMaintenance maintenance;
    UUID exam, user, owner, proctor, other, session;
    static final byte[] JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 1, 2, 3, 4, (byte) 0xFF, (byte) 0xD9};
    IntegrityPolicy policy(boolean camera) { return new IntegrityPolicy(true, true, false, true, true, true, IntegrityPolicy.WarningPolicy.WARN_ONLY, 5, camera); }
    String token(UUID id, String role) {
        return "Bearer " + Jwts.builder().subject(id.toString()).claim("role", role).claim("systemPrivilege", "NONE").claim("type", "access")
                .expiration(new Date(System.currentTimeMillis() + 60000)).signWith(Keys.hmacShaKeyFor("unit-test-only-signing-key-32-bytes-minimum".getBytes(StandardCharsets.UTF_8))).compact();
    }
    UUID start(IntegrityPolicy p, UUID student) {
        when(exams.authorize(eq(exam), any(), eq("test-internal"))).thenReturn(new ExamClient.ExamDto(exam, "Camera", 10, 5, 3, null, false, false, p));
        return core.startExam(exam, student).sessionId();
    }
    @BeforeEach void setup() {
        user = UUID.randomUUID(); owner = UUID.randomUUID(); proctor = UUID.randomUUID(); other = UUID.randomUUID();
        stubExam(UUID.randomUUID());
        session = start(policy(true), user);
    }
    void stubExam(UUID id) {
        exam = id;
        when(exams.safe(eq(exam), eq("test-internal"))).thenReturn(List.of(new ExamClient.Question(UUID.randomUUID(), 1, "SHORT_ANSWER", "Q", List.of(), BigDecimal.TEN, 0)));
        when(exams.reserveStart(any(), any(), eq("test-internal"))).thenAnswer(c -> new ExamClient.StartReservation(c.getArgument(0), UUID.randomUUID(), 1));
        when(exams.claimStart(any(), any(), any(), eq("test-internal"))).thenAnswer(c -> { Instant now = Instant.now(); return new ExamClient.ScheduleAdmission(c.getArgument(1), 1, now, now.plusSeconds(600)); });
        when(exams.permission(eq(exam), any(), eq(false), eq("test-internal"))).thenAnswer(c -> {
            UUID a = c.getArgument(1); if (a.equals(owner)) return "OWNER"; if (a.equals(proctor)) return "PROCTOR";
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No exam access"); });
    }
    org.springframework.test.web.servlet.ResultActions event(UUID s, UUID actor, String type, byte[] image, String detail) throws Exception {
        String body = "{\"clientEventId\":\"" + UUID.randomUUID() + "\",\"type\":\"" + type + "\",\"occurredAt\":\"" + Instant.now() + "\"" + (detail == null ? "" : ",\"detail\":" + detail) + "}";
        var req = multipart("/sessions/" + s + "/camera/events").file(new MockMultipartFile("event", "", "application/json", body.getBytes(StandardCharsets.UTF_8)));
        if (image != null) req.file(new MockMultipartFile("snapshot", "s.jpg", "image/jpeg", image));
        return mvc.perform(req.header("Authorization", token(actor, "STUDENT")));
    }
    org.springframework.test.web.servlet.ResultActions chunk(UUID s, int seq, String type, byte[] data) throws Exception {
        return mvc.perform(post("/sessions/" + s + "/camera/video").param("seq", String.valueOf(seq)).contentType(type).content(data).header("Authorization", token(user, "STUDENT")));
    }

    @Test void studentReportsEvidenceWithSnapshotsAndProctorsReviewIt() throws Exception {
        event(session, user, "REFERENCE", JPEG, "{\"faces\":1}").andExpect(status().isOk()).andExpect(jsonPath("$.event.reason").value("Ảnh khuôn mặt lúc bắt đầu"));
        event(session, user, "FACE_MISMATCH", JPEG, "{\"distance\":0.71,\"threshold\":0.55}").andExpect(status().isOk());
        event(session, user, "MULTIPLE_FACES", JPEG, "{\"faces\":2}").andExpect(status().isOk());
        event(session, user, "CAMERA_OFF", null, null).andExpect(status().isOk()).andExpect(jsonPath("$.event.hasSnapshot").value(false));

        String body = mvc.perform(get("/sessions/" + session + "/camera").header("Authorization", token(proctor, "TEACHER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reference.type").value("REFERENCE"))
                .andExpect(jsonPath("$.events.length()").value(3)).andExpect(jsonPath("$.events[0].reason").value("Khuôn mặt không khớp với ảnh lúc bắt đầu"))
                .andExpect(jsonPath("$.events[0].detail.distance").value(0.71)).andReturn().getResponse().getContentAsString();
        JsonNode tree = json.readTree(body);
        String eventId = tree.path("events").path(1).path("id").asText();
        byte[] image = mvc.perform(get("/sessions/" + session + "/camera/events/" + eventId + "/snapshot").header("Authorization", token(owner, "TEACHER")))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "image/jpeg")).andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(JPEG, image);

        mvc.perform(get("/sessions/exam/" + exam + "/camera").header("Authorization", token(owner, "TEACHER")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].sessionId").value(session.toString())).andExpect(jsonPath("$[0].violations").value(3))
                .andExpect(jsonPath("$[0].counts.REFERENCE").value(1));
        // Students and unrelated teachers never see review routes.
        mvc.perform(get("/sessions/" + session + "/camera").header("Authorization", token(user, "STUDENT"))).andExpect(status().isForbidden());
        mvc.perform(get("/sessions/" + session + "/camera").header("Authorization", token(other, "TEACHER"))).andExpect(status().isForbidden());
        mvc.perform(get("/sessions/" + session + "/camera/events/" + eventId + "/snapshot").header("Authorization", token(user, "STUDENT"))).andExpect(status().isForbidden());
    }

    @Test void inputIsBoundedAndOnlyTheOwnerOfACameraAttemptMayReport() throws Exception {
        event(session, other, "NO_FACE", JPEG, null).andExpect(status().isForbidden());
        event(session, user, "NO_FACE", null, null).andExpect(status().isBadRequest()); // snapshot required
        event(session, user, "NO_FACE", "not a jpeg".getBytes(), null).andExpect(status().isUnsupportedMediaType());
        event(session, user, "NO_FACE", new byte[400 * 1024], null).andExpect(status().is4xxClientError());
        event(session, user, "NO_FACE", JPEG, "{\"name\":1}").andExpect(status().isBadRequest());
        event(session, user, "NO_FACE", JPEG, "{\"faces\":\"x\"}").andExpect(status().isBadRequest());
        event(session, user, "WEBCAM", JPEG, null).andExpect(status().isBadRequest());
        String withExtra = "{\"clientEventId\":\"" + UUID.randomUUID() + "\",\"type\":\"NO_FACE\",\"userId\":\"" + other + "\"}";
        mvc.perform(multipart("/sessions/" + session + "/camera/events").file(new MockMultipartFile("event", "", "application/json", withExtra.getBytes()))
                .file(new MockMultipartFile("snapshot", "s.jpg", "image/jpeg", JPEG)).header("Authorization", token(user, "STUDENT"))).andExpect(status().isBadRequest());
        // Same clientEventId is idempotent.
        String id = UUID.randomUUID().toString(), same = "{\"clientEventId\":\"" + id + "\",\"type\":\"LOOKING_AWAY\",\"detail\":{\"yaw\":42,\"seconds\":5}}";
        for (boolean dup : List.of(false, true))
            mvc.perform(multipart("/sessions/" + session + "/camera/events").file(new MockMultipartFile("event", "", "application/json", same.getBytes()))
                    .file(new MockMultipartFile("snapshot", "s.jpg", "image/jpeg", JPEG)).header("Authorization", token(user, "STUDENT")))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.duplicate").value(dup));
        // Rate limit (6/min in this test): answering is not affected.
        int codes429 = 0;
        for (int i = 0; i < 6; i++) if (event(session, user, "CAMERA_ON", null, null).andReturn().getResponse().getStatus() == 429) codes429++;
        assertTrue(codes429 > 0);
        // Attempts without camera policy refuse camera traffic.
        UUID plain = start(policy(false), other);
        event(plain, other, "CAMERA_ON", null, null).andExpect(status().isConflict());
        mvc.perform(post("/sessions/" + plain + "/camera/video").param("seq", "0").contentType("video/webm").content(new byte[]{1}).header("Authorization", token(other, "STUDENT")))
                .andExpect(status().isConflict());
    }

    @Test void recordingChunksAreStoredOnceInOrderAndStreamedToStaff() throws Exception {
        chunk(session, 1, "video/webm", new byte[]{4, 5}).andExpect(status().isOk());
        chunk(session, 0, "video/webm;codecs=vp8", new byte[]{1, 2, 3}).andExpect(status().isOk()).andExpect(jsonPath("$.chunks").value(2)).andExpect(jsonPath("$.bytes").value(5));
        chunk(session, 0, "video/webm", new byte[]{9, 9, 9}).andExpect(status().isOk()).andExpect(jsonPath("$.stored").value(false)).andExpect(jsonPath("$.chunks").value(2));
        chunk(session, 2, "video/mp4", new byte[]{7}).andExpect(status().isConflict());
        chunk(session, 2, "image/png", new byte[]{7}).andExpect(status().isUnsupportedMediaType());
        chunk(session, -1, "video/webm", new byte[]{7}).andExpect(status().isBadRequest());
        // The last chunk may arrive right after submit.
        core.submitExam(session, user);
        chunk(session, 2, "video/webm", new byte[]{6}).andExpect(status().isOk());
        byte[] video = mvc.perform(get("/sessions/" + session + "/camera/video").header("Authorization", token(proctor, "TEACHER")))
                .andExpect(status().isOk()).andExpect(header().string("Content-Type", "video/webm")).andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(new byte[]{1, 2, 3, 4, 5, 6}, video, "chunks concatenate in seq order; a retried chunk is not rewritten");
        mvc.perform(get("/sessions/" + session + "/camera").header("Authorization", token(owner, "TEACHER")))
                .andExpect(jsonPath("$.recording.chunks").value(3)).andExpect(jsonPath("$.recording.bytes").value(6)).andExpect(jsonPath("$.recording.mimeType").value("video/webm"));
        // Long after the attempt ended, uploads are refused.
        db.update("update exam_sessions set end_time=end_time - interval '10 minutes', expires_at=expires_at - interval '20 minutes' where id=?", session);
        chunk(session, 3, "video/webm", new byte[]{8}).andExpect(status().isConflict());
        mvc.perform(get("/sessions/" + session + "/camera/video").header("Authorization", token(user, "STUDENT"))).andExpect(status().isForbidden());
    }

    @Test void retentionDeletesMediaAfterTheDeadlineButKeepsTheLog() throws Exception {
        event(session, user, "REFERENCE", JPEG, "{\"faces\":1}").andExpect(status().isOk());
        event(session, user, "NO_FACE", JPEG, "{\"seconds\":12}").andExpect(status().isOk());
        chunk(session, 0, "video/webm", new byte[]{0x1A, 0x45, (byte) 0xDF, (byte) 0xA3, 1, 2}).andExpect(status().isOk());
        UUID recent = start(policy(true), other);
        event(recent, other, "REFERENCE", JPEG, "{\"faces\":1}").andExpect(status().isOk());

        String fresh = mvc.perform(get("/sessions/" + session + "/camera").header("Authorization", token(proctor, "TEACHER"))).andReturn().getResponse().getContentAsString();
        assertEquals(30, json.readTree(fresh).at("/retention/days").asInt());
        assertFalse(json.readTree(fresh).at("/retention/deleteAt").isNull());
        assertTrue(json.readTree(fresh).at("/retention/purgedAt").isNull());
        assertEquals(0, retention.purge(Instant.now()), "attempts in progress keep their media");

        // The first attempt ended 31 days ago, the other one is still within the retention period.
        db.update("update exam_sessions set status='SUBMITTED', expires_at=now() - interval '31 days' where id=?", session);
        db.update("update exam_sessions set status='SUBMITTED', expires_at=now() - interval '2 days' where id=?", recent);
        assertEquals(1, retention.purge(Instant.now()));
        assertEquals(0, retention.purge(Instant.now()), "idempotent");

        String body = mvc.perform(get("/sessions/" + session + "/camera").header("Authorization", token(proctor, "TEACHER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode cam = json.readTree(body);
        assertFalse(cam.at("/retention/purgedAt").isNull());
        assertEquals(1, cam.at("/events").size(), "the violation log stays");
        assertFalse(cam.at("/events/0/hasSnapshot").asBoolean());
        assertFalse(cam.at("/reference/hasSnapshot").asBoolean());
        assertEquals(0, cam.at("/recording/bytes").asLong());
        mvc.perform(get("/sessions/" + session + "/camera/video").header("Authorization", token(proctor, "TEACHER"))).andExpect(status().isGone());
        mvc.perform(get("/sessions/" + session + "/camera/events/" + cam.at("/events/0/id").asText() + "/snapshot").header("Authorization", token(proctor, "TEACHER"))).andExpect(status().isGone());
        assertEquals(3, db.queryForObject("select files from camera_media_purges where session_id=?", Integer.class, session), "2 snapshots + 1 chunk");

        String list = mvc.perform(get("/sessions/exam/" + exam + "/camera").header("Authorization", token(owner, "TEACHER"))).andReturn().getResponse().getContentAsString();
        for (JsonNode row : json.readTree(list)) assertEquals(row.get("sessionId").asText().equals(session.toString()), !row.at("/retention/purgedAt").isNull());
        String kept = mvc.perform(get("/sessions/" + recent + "/camera").header("Authorization", token(proctor, "TEACHER"))).andReturn().getResponse().getContentAsString();
        assertTrue(json.readTree(kept).at("/reference/hasSnapshot").asBoolean());
    }

    String vector(double base, double step) {
        var sb = new StringBuilder("{\"descriptor\":[");
        for (int i = 0; i < 128; i++) sb.append(i == 0 ? "" : ",").append(base + step * Math.sin(i));
        return sb.append("]}").toString();
    }
    org.springframework.test.web.servlet.ResultActions verify(UUID s, UUID actor, String identity) throws Exception {
        return mvc.perform(multipart("/sessions/" + s + "/camera/identity")
                .file(new MockMultipartFile("identity", "", "application/json", identity.getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("photo", "p.jpg", "image/jpeg", JPEG)).header("Authorization", token(actor, "STUDENT")));
    }
    void finish(UUID s) { db.update("update exam_sessions set status='SUBMITTED' where id=?", s); }

    @Test void faceProfileIsCreatedOnceThenEveryCameraExamIsVerifiedAgainstIt() throws Exception {
        verify(session, user, "{\"descriptor\":[0.1,0.2]}").andExpect(status().isBadRequest());
        verify(session, other, vector(0, 0.1)).andExpect(status().isForbidden());
        event(session, user, "PROFILE_MISMATCH", JPEG, null).andExpect(status().isBadRequest());
        verify(session, user, vector(0, 0.1)).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("ENROLLED")).andExpect(jsonPath("$.mayContinue").value(true));
        verify(session, user, vector(5, 0.1)).andExpect(jsonPath("$.outcome").value("ENROLLED")); // settled: not re-enrolled
        finish(session);

        // Second camera exam, same person (small distance).
        stubExam(UUID.randomUUID());
        UUID second = start(policy(true), user);
        verify(second, user, vector(0.01, 0.1)).andExpect(status().isOk()).andExpect(jsonPath("$.outcome").value("MATCH"))
                .andExpect(jsonPath("$.distance").value(org.hamcrest.Matchers.lessThan(0.2)));
        finish(second);

        // Third exam, someone else: three mismatches, then the student may continue and the attempt is flagged.
        stubExam(UUID.randomUUID());
        UUID third = start(policy(true), user);
        verify(third, user, vector(0.3, 0.1)).andExpect(jsonPath("$.outcome").value("MISMATCH")).andExpect(jsonPath("$.attempts").value(1)).andExpect(jsonPath("$.mayContinue").value(false));
        verify(third, user, vector(0.3, 0.1)).andExpect(jsonPath("$.attempts").value(2));
        verify(third, user, vector(0.3, 0.1)).andExpect(jsonPath("$.attempts").value(3)).andExpect(jsonPath("$.mayContinue").value(true));
        String body = mvc.perform(get("/sessions/" + third + "/camera").header("Authorization", token(proctor, "TEACHER"))).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        JsonNode cam = json.readTree(body);
        assertEquals("MISMATCH", cam.at("/identity/outcome").asText());
        assertTrue(cam.at("/profile").asBoolean());
        assertEquals(3, cam.at("/events").size());
        assertEquals("PROFILE_MISMATCH", cam.at("/events/0/type").asText());
        assertTrue(cam.at("/events/0/hasSnapshot").asBoolean());
        String list = mvc.perform(get("/sessions/exam/" + exam + "/camera").header("Authorization", token(owner, "TEACHER"))).andReturn().getResponse().getContentAsString();
        assertEquals("MISMATCH", json.readTree(list).at("/0/identity").asText());
        assertEquals(3, json.readTree(list).at("/0/violations").asInt());

        // Staff compare with the profile photo; only the owner/manager may reset it.
        mvc.perform(get("/sessions/" + third + "/camera/profile-photo").header("Authorization", token(proctor, "TEACHER"))).andExpect(status().isOk()).andExpect(content().bytes(JPEG));
        mvc.perform(get("/sessions/" + third + "/camera/profile-photo").header("Authorization", token(other, "TEACHER"))).andExpect(status().isForbidden());
        mvc.perform(delete("/sessions/" + third + "/camera/profile").header("Authorization", token(proctor, "TEACHER"))).andExpect(status().isForbidden());
        mvc.perform(delete("/sessions/" + third + "/camera/profile").header("Authorization", token(owner, "TEACHER"))).andExpect(status().isNoContent());
        mvc.perform(get("/sessions/" + third + "/camera/profile-photo").header("Authorization", token(proctor, "TEACHER"))).andExpect(status().isNotFound());
        assertEquals(0, db.queryForObject("select count(*) from face_profiles where user_id=?", Integer.class, user));
    }

    @Test void identityCheckNeedsCameraProctoring() throws Exception {
        finish(session);
        stubExam(UUID.randomUUID());
        UUID plain = start(policy(false), user);
        verify(plain, user, vector(0, 0.1)).andExpect(status().isConflict());
    }
}

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
class SpeakingAnswerTest {
    @DynamicPropertySource static void storage(DynamicPropertyRegistry r) throws Exception {
        var dir = Files.createTempDirectory("camera-test"); r.add("exam.camera.storage-dir", dir::toString);
        var audio = Files.createTempDirectory("answer-audio-test"); r.add("exam.answer-audio.storage-dir", audio::toString);
    }
    @Autowired SessionService core; @Autowired MockMvc mvc; @Autowired ObjectMapper json; @Autowired JdbcTemplate db;
    @MockBean ExamClient exams; @MockBean SessionMaintenance maintenance;
    UUID exam, user, owner, proctor, other, session, speakingQ = UUID.randomUUID(), textQ = UUID.randomUUID();
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
        when(exams.safe(eq(exam), eq("test-internal"))).thenReturn(List.of(
                new ExamClient.Question(speakingQ, 1, "SPEAKING", "Giới thiệu bản thân", List.of(), BigDecimal.TEN, 0, Map.of("maxRecordSeconds", 60)),
                new ExamClient.Question(textQ, 1, "SHORT_ANSWER", "Q", List.of(), BigDecimal.ONE, 1)));
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

    static final byte[] WEBM = new byte[2048];
    static { WEBM[0] = 0x1A; WEBM[1] = 0x45; WEBM[2] = (byte) 0xDF; WEBM[3] = (byte) 0xA3; }
    org.springframework.test.web.servlet.ResultActions record(UUID q, UUID actor, int seconds, String type, byte[] data) throws Exception {
        return mvc.perform(post("/sessions/" + session + "/answers/" + q + "/audio").param("seconds", String.valueOf(seconds)).contentType(type).content(data).header("Authorization", token(actor, "STUDENT")));
    }

    @Test void speakingAnswersAreRecordingsThatOnlyTheStudentAndStaffCanPlay() throws Exception {
        mvc.perform(post("/sessions/" + session + "/answers").param("questionId", speakingQ.toString()).contentType("application/json")
                .content("{\"content\":\"typed instead of spoken\"}").header("Authorization", token(user, "STUDENT"))).andExpect(status().isBadRequest());
        record(textQ, user, 10, "audio/webm", WEBM).andExpect(status().isBadRequest());
        record(speakingQ, user, 90, "audio/webm", WEBM).andExpect(status().isBadRequest()); // above the 60 s limit
        record(speakingQ, user, 20, "text/plain", WEBM).andExpect(status().isUnsupportedMediaType());
        record(speakingQ, other, 20, "audio/webm", WEBM).andExpect(status().isForbidden());
        record(speakingQ, user, 20, "audio/webm;codecs=opus", WEBM).andExpect(status().isOk()).andExpect(jsonPath("$.seconds").value(20));
        record(speakingQ, user, 31, "audio/webm", WEBM).andExpect(status().isOk()); // recording again replaces it

        String answers = mvc.perform(get("/sessions/" + session).header("Authorization", token(user, "STUDENT"))).andReturn().getResponse().getContentAsString();
        JsonNode content = json.readTree(json.readTree(answers).at("/answers/" + speakingQ).asText());
        assertTrue(content.path("audio").asBoolean()); assertEquals(31, content.path("seconds").asInt());
        assertEquals(60, json.readTree(answers).at("/questions/0/presentation/maxRecordSeconds").asInt());

        mvc.perform(get("/sessions/" + session + "/answers/" + speakingQ + "/audio").header("Authorization", token(user, "STUDENT")))
                .andExpect(status().isOk()).andExpect(content().contentType("audio/webm")).andExpect(content().bytes(WEBM));
        mvc.perform(get("/sessions/" + session + "/answers/" + speakingQ + "/audio").header("Authorization", token(proctor, "TEACHER"))).andExpect(status().isOk());
        mvc.perform(get("/sessions/" + session + "/answers/" + speakingQ + "/audio").header("Authorization", token(other, "TEACHER"))).andExpect(status().isForbidden());
        mvc.perform(get("/sessions/" + session + "/answers/" + textQ + "/audio").header("Authorization", token(user, "STUDENT"))).andExpect(status().isNotFound());

        db.update("update exam_sessions set status='SUBMITTED' where id=?", session);
        record(speakingQ, user, 20, "audio/webm", WEBM).andExpect(status().isConflict());
    }
}

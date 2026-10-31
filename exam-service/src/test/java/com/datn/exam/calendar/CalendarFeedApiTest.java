package com.datn.exam.calendar;

import com.datn.exam.client.QuestionBankClient;
import com.datn.exam.client.SessionClient;
import com.datn.exam.dto.Snapshot;
import com.datn.exam.entity.Exam;
import com.datn.exam.entity.ExamParticipant;
import com.datn.exam.repository.ExamParticipantRepository;
import com.datn.exam.repository.ExamRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {"spring.datasource.url=jdbc:postgresql://localhost:5432/exam_test", "spring.datasource.username=postgres", "spring.datasource.password=",
        "eureka.client.enabled=false", "spring.cloud.discovery.enabled=false", "jwt.secret=unit-test-only-signing-key-32-bytes-minimum", "exam.internal-token=test-internal",
        "exam.calendar.app-url=https://examini.example/", "exam.events.outbox-initial-delay-ms=3600000", "exam.events.outbox-poll-ms=3600000",
        "exam.schedule.sweep-ms=3600000", "exam.start-admission-reconcile-ms=3600000"})
@AutoConfigureMockMvc
class CalendarFeedApiTest {
    @Autowired MockMvc mvc;
    @Autowired ExamRepository exams;
    @Autowired ExamParticipantRepository participants;
    @Autowired JdbcTemplate jdbc;
    @MockBean SessionClient sessions;
    @MockBean QuestionBankClient questions;
    final ObjectMapper json = new ObjectMapper();
    UUID owner, student, stranger;

    static String token(UUID id, String role) {
        return "Bearer " + Jwts.builder().subject(id.toString()).claim("role", role).claim("type", "access").expiration(new Date(System.currentTimeMillis() + 60000))
                .signWith(Keys.hmacShaKeyFor("unit-test-only-signing-key-32-bytes-minimum".getBytes(StandardCharsets.UTF_8))).compact();
    }

    @BeforeEach void setup() {
        owner = UUID.randomUUID(); student = UUID.randomUUID(); stranger = UUID.randomUUID();
        when(sessions.availability(any(), any(), any())).thenReturn(new SessionClient.Availability(0, false, null, 0, null, null));
        when(sessions.eligibility(any(), any(), any())).thenReturn(new SessionClient.Eligibility(0, false));
    }

    Exam exam(String title, Instant start, Exam.Status status) {
        Exam e = Exam.builder().code(UUID.randomUUID().toString()).title(title).durationMinutes(45).status(status).createdBy(owner)
                .startAt(start).endAt(start == null ? null : start.plus(90, ChronoUnit.MINUTES)).timezone("Asia/Ho_Chi_Minh").deadlineRule(Exam.DeadlineRule.CAPPED_AT_END)
                .snapshots(new ArrayList<>(List.of(new Snapshot(UUID.randomUUID(), 1, "MULTIPLE_CHOICE", "Q", List.of(new Snapshot.Option(UUID.randomUUID(), "A", 0, true),
                        new Snapshot.Option(UUID.randomUUID(), "B", 1, false)), BigDecimal.ONE, null, 0)))).build();
        e = exams.saveAndFlush(e);
        participants.saveAndFlush(new ExamParticipant(e.getId(), student, "ASSIGNED", owner, Instant.now()));
        return e;
    }

    @Test void studentSubscribesAndTheFeedFollowsScheduleChanges() throws Exception {
        Instant soon = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MINUTES);
        Exam scheduled = exam("Kiểm tra cuối kỳ, Toán", soon, Exam.Status.PUBLISHED);
        Exam unscheduled = exam("Chưa có lịch", null, Exam.Status.PUBLISHED);

        mvc.perform(get("/exams/calendar/feed").header("Authorization", token(student, "STUDENT"))).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        String created = mvc.perform(post("/exams/calendar/feed").header("Authorization", token(student, "STUDENT")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String feedToken = json.readTree(created).path("token").asText();
        assertTrue(feedToken.length() >= 30);
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM calendar_feeds WHERE token_hash = ?", Integer.class, feedToken), "stored hashed only");

        String ics = mvc.perform(get("/exams/calendar/feed/" + feedToken + ".ics")).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/calendar")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String unfolded = ics.replace("\r\n ", "");
        assertTrue(unfolded.contains("UID:exam-" + scheduled.getId() + "@examini"));
        assertTrue(unfolded.contains("SUMMARY:Thi: Kiểm tra cuối kỳ\\, Toán"));
        assertTrue(unfolded.contains("https://examini.example/exam/lobby/" + scheduled.getId()));
        assertFalse(unfolded.contains(unscheduled.getId().toString()), "no start time → not on the calendar");

        // moved by the teacher: the same UID with a later time and a higher SEQUENCE
        scheduled.setStartAt(soon.plus(1, ChronoUnit.DAYS)); scheduled.setEndAt(soon.plus(1, ChronoUnit.DAYS).plus(90, ChronoUnit.MINUTES));
        scheduled.setScheduleRevision(scheduled.getScheduleRevision() + 1); exams.saveAndFlush(scheduled);
        String moved = mvc.perform(get("/exams/calendar/feed/" + feedToken + ".ics")).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(moved.contains("SEQUENCE:" + scheduled.getScheduleRevision()));
        assertNotEquals(ics.substring(ics.indexOf("DTSTART:")), moved.substring(moved.indexOf("DTSTART:")));

        // a new link invalidates the old one; revoking ends the subscription
        mvc.perform(post("/exams/calendar/feed").header("Authorization", token(student, "STUDENT"))).andExpect(status().isCreated());
        mvc.perform(get("/exams/calendar/feed/" + feedToken + ".ics")).andExpect(status().isNotFound());
        mvc.perform(delete("/exams/calendar/feed").header("Authorization", token(student, "STUDENT"))).andExpect(status().isNoContent());
        mvc.perform(get("/exams/calendar/feed").header("Authorization", token(student, "STUDENT"))).andExpect(jsonPath("$.active").value(false));
        mvc.perform(get("/exams/calendar/feed/not-a-real-token-at-all-xx.ics")).andExpect(status().isNotFound());
    }

    @Test void singleExamDownloadRespectsVisibility() throws Exception {
        Exam e = exam("Thi thử", Instant.now().plus(2, ChronoUnit.DAYS), Exam.Status.PUBLISHED);
        String ics = mvc.perform(get("/exams/" + e.getId() + "/calendar.ics").header("Authorization", token(student, "STUDENT")))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(ics.contains("BEGIN:VEVENT") && ics.contains("TRIGGER:-PT1440M"));
        String staff = mvc.perform(get("/exams/" + e.getId() + "/calendar.ics").header("Authorization", token(owner, "TEACHER")))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(staff.replace("\r\n ", "").contains("SUMMARY:[Quản lý] Thi thử"));
        mvc.perform(get("/exams/" + e.getId() + "/calendar.ics").header("Authorization", token(stranger, "STUDENT"))).andExpect(status().isNotFound());
        mvc.perform(get("/exams/" + e.getId() + "/calendar.ics")).andExpect(status().isUnauthorized());
        // a feed URL never accepts other credentials
        mvc.perform(get("/exams/calendar/feed/abcdefghijklmnopqrstuvwxyz.ics").header("Authorization", token(student, "STUDENT"))).andExpect(status().isUnauthorized());
    }
}

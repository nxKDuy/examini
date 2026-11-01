package com.datn.examsession.service;

import com.datn.examsession.client.ExamClient;
import com.datn.examsession.entity.ExamSession;
import com.datn.examsession.entity.SessionAnswer;
import com.datn.examsession.repository.SessionAnswerRepository;
import com.datn.examsession.repository.SessionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

/**
 * Voice answers of SPEAKING questions: one recording per question and attempt, replaced when the student records
 * again. The answer content becomes {"audio":true,"seconds":n,"bytes":n,"type":"audio/webm"} so grading knows a
 * recording exists; the bytes are read back by the student or by the exam's staff only. Not part of camera retention.
 */
@Service
public class AnswerAudioService {
    public static final int MAX_BYTES = 12 * 1024 * 1024;
    private static final Set<String> TYPES = Set.of("audio/webm", "audio/ogg", "audio/mp4", "audio/mpeg");
    private final SessionRepository sessions;
    private final SessionAnswerRepository answers;
    private final CameraProctoringService staff;
    private final ObjectMapper json;
    private final Path root;

    public AnswerAudioService(SessionRepository sessions, SessionAnswerRepository answers, CameraProctoringService staff, ObjectMapper json,
                              @Value("${exam.answer-audio.storage-dir:${java.io.tmpdir}/examini-answer-audio}") String dir) {
        this.sessions = sessions; this.answers = answers; this.staff = staff; this.json = json;
        this.root = Path.of(dir).toAbsolutePath().normalize();
    }

    private static ResponseStatusException error(int s, String m) { return new ResponseStatusException(HttpStatus.valueOf(s), m); }
    private Path file(ExamSession s, UUID question) { return root.resolve(s.getExamId().toString()).resolve(s.getId().toString()).resolve(question + ".audio"); }

    private static String mime(String contentType) {
        String m = contentType == null ? "" : contentType.split(";")[0].strip().toLowerCase(Locale.ROOT);
        return m.equals("audio/x-m4a") ? "audio/mp4" : m;
    }

    public record Saved(UUID questionId, int seconds, int bytes, String type) {}

    @Transactional
    public Saved upload(UUID sessionId, UUID actor, UUID questionId, String contentType, byte[] data, int seconds) {
        var s = sessions.lockById(sessionId).orElseThrow(() -> error(404, "Session not found"));
        if (!s.getUserId().equals(actor)) throw error(403, "Not your session");
        if (s.getStatus() != ExamSession.Status.IN_PROGRESS || !Instant.now().isBefore(s.getExpiresAt())) throw error(409, "Session ended");
        ExamClient.Question q = s.getQuestions().stream().filter(v -> v.questionId().equals(questionId)).findFirst()
                .orElseThrow(() -> error(400, "Question outside this exam"));
        if (!"SPEAKING".equals(q.questionType())) throw error(400, "Only speaking questions take a recording");
        String type = mime(contentType);
        if (!TYPES.contains(type)) throw error(415, "Recording must be audio/webm, audio/ogg, audio/mp4 or audio/mpeg");
        if (data == null || data.length < 64) throw error(400, "Empty recording");
        if (data.length > MAX_BYTES) throw error(413, "Recording too large");
        int limit = q.maxRecordSeconds() == null ? 120 : q.maxRecordSeconds();
        if (seconds < 1 || seconds > limit + 5) throw error(400, "Recording length must be 1.." + limit + " seconds");
        Path f = file(s, questionId);
        try {
            Files.createDirectories(f.getParent());
            Path tmp = f.resolveSibling(questionId + ".part");
            Files.write(tmp, data);
            Files.move(tmp, f, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) { throw error(503, "Recording storage unavailable"); }
        var saved = new Saved(questionId, seconds, data.length, type);
        String content;
        try { content = json.writeValueAsString(Map.of("audio", true, "seconds", seconds, "bytes", data.length, "type", type)); }
        catch (IOException e) { throw new IllegalStateException(e); }
        var a = answers.findBySessionIdAndQuestionId(sessionId, questionId).orElseGet(() -> SessionAnswer.builder().session(s).questionId(questionId).build());
        a.setAnswerContent(content); answers.save(a);
        return saved;
    }

    public record Audio(String type, byte[] bytes) {}

    /** The student of the attempt, or exam staff (owner, manager, proctor) for grading. */
    @Transactional(readOnly = true)
    public Audio read(UUID sessionId, UUID actor, String role, UUID questionId) {
        var s = sessions.findById(sessionId).orElseThrow(() -> error(404, "Session not found"));
        if (!s.getUserId().equals(actor)) staff.reviewed(sessionId, actor, role);
        var a = answers.findBySessionIdAndQuestionId(sessionId, questionId).orElseThrow(() -> error(404, "No recording"));
        String type;
        try { var node = json.readTree(a.getAnswerContent() == null ? "{}" : a.getAnswerContent()); if (!node.path("audio").asBoolean()) throw error(404, "No recording"); type = node.path("type").asText("audio/webm"); }
        catch (IOException e) { throw error(404, "No recording"); }
        try { return new Audio(type, Files.readAllBytes(file(s, questionId))); }
        catch (IOException e) { throw error(404, "Recording missing"); }
    }
}

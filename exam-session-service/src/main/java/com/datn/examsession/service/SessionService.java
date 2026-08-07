package com.datn.examsession.service;

import com.datn.examsession.client.ExamClient;
import com.datn.examsession.client.QuestionBankClient;
import com.datn.examsession.config.RabbitMQConfig;
import com.datn.examsession.entity.ExamSession;
import com.datn.examsession.entity.SessionAnswer;
import com.datn.examsession.repository.SessionAnswerRepository;
import com.datn.examsession.repository.SessionRepository;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class SessionService {

    private final SessionRepository sessionRepo;
    private final SessionAnswerRepository answerRepo;
    private final ExamClient examClient;
    private final QuestionBankClient questionClient;
    private final StringRedisTemplate redisTemplate;
    private final RabbitTemplate rabbitTemplate;

    /**
     * Bắt đầu thi. Trả về danh sách câu hỏi đã shuffle (kèm options không có đáp án).
     */
    @Transactional
    public StartSessionResponse startExam(String examCode, UUID userId) {
        // 1. Lấy thông tin Exam từ exam-service
        ExamClient.ExamDto exam;
        try {
            exam = examClient.getByCode(examCode);
        } catch (FeignException.NotFound e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đề thi với mã: " + examCode);
        }

        if (!"PUBLISHED".equals(exam.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Đề thi chưa được phát hành");
        }

        // 2. Kiểm tra xem user đã thi chưa
        if (sessionRepo.findActiveSession(exam.id(), userId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Bạn đang có một phiên thi chưa kết thúc");
        }

        // 3. Tạo session
        ExamSession session = ExamSession.builder()
                .examId(exam.id())
                .userId(userId)
                .startTime(LocalDateTime.now())
                .status(ExamSession.Status.IN_PROGRESS)
                .build();
        session = sessionRepo.save(session);

        // 4. Lấy danh sách question IDs đã shuffle riêng cho user này
        List<UUID> shuffledQuestionIds = examClient.getShuffledQuestionsForStudent(exam.id(), userId);

        // 5. Fetch chi tiết questions từ question-bank-service
        List<QuestionBankClient.QuestionResponse> questions = questionClient.getByIds(shuffledQuestionIds);

        // 6. Set Redis TTL để đếm giờ server-side
        String redisKey = "exam_session:timeout:" + session.getId();
        redisTemplate.opsForValue().set(redisKey, "ACTIVE", exam.durationMinutes(), TimeUnit.MINUTES);

        return new StartSessionResponse(session.getId(), exam, questions);
    }

    /**
     * Lưu câu trả lời của 1 câu hỏi
     */
    @Transactional
    public void saveAnswer(UUID sessionId, UUID userId, UUID questionId, String answerContent) {
        ExamSession session = getSessionOrThrow(sessionId);
        if (!session.getUserId().equals(userId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền");
        if (session.getStatus() != ExamSession.Status.IN_PROGRESS) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phiên thi đã kết thúc");

        SessionAnswer answer = answerRepo.findBySessionIdAndQuestionId(sessionId, questionId)
                .orElse(SessionAnswer.builder().session(session).questionId(questionId).build());

        answer.setAnswerContent(answerContent);
        answerRepo.save(answer);
    }

    /**
     * Nộp bài (manual)
     */
    @Transactional
    public void submitExam(UUID sessionId, UUID userId) {
        ExamSession session = getSessionOrThrow(sessionId);
        if (!session.getUserId().equals(userId)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Không có quyền");
        if (session.getStatus() != ExamSession.Status.IN_PROGRESS) return; // Đã nộp rồi

        session.setStatus(ExamSession.Status.SUBMITTED);
        session.setEndTime(LocalDateTime.now());
        sessionRepo.save(session);

        // Xóa Redis key đếm giờ
        redisTemplate.delete("exam_session:timeout:" + sessionId);

        // Publish event cho grading-service chấm điểm
        publishSubmittedEvent(sessionId);
    }

    /**
     * Nộp bài tự động (khi Redis key hết hạn)
     */
    @Transactional
    public void autoSubmitExam(UUID sessionId) {
        sessionRepo.findById(sessionId).ifPresent(session -> {
            if (session.getStatus() == ExamSession.Status.IN_PROGRESS) {
                session.setStatus(ExamSession.Status.AUTO_SUBMITTED);
                session.setEndTime(LocalDateTime.now());
                sessionRepo.save(session);
                publishSubmittedEvent(sessionId);
            }
        });
    }

    private void publishSubmittedEvent(UUID sessionId) {
        // Gửi ID session qua RabbitMQ để service chấm điểm xử lý (fire and forget)
        // ponytail: Chỉ truyền ID, grading-service tự query DB lấy answers, không payload to
        rabbitTemplate.convertAndSend(RabbitMQConfig.EXAM_SUBMITTED_QUEUE, sessionId.toString());
    }

    private ExamSession getSessionOrThrow(UUID id) {
        return sessionRepo.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Session không tồn tại"));
    }

    public SessionDetailDto getSessionDetails(UUID sessionId) {
        ExamSession session = getSessionOrThrow(sessionId);
        List<SessionDetailDto.AnswerDto> answers = answerRepo.findAll().stream() // Ideally this should be a custom query
                .filter(a -> a.getSession().getId().equals(sessionId))
                .map(a -> new SessionDetailDto.AnswerDto(a.getQuestionId(), a.getAnswerContent()))
                .toList();
        return new SessionDetailDto(session.getExamId(), session.getUserId(), answers);
    }

    public record SessionDetailDto(UUID examId, UUID userId, List<AnswerDto> answers) {
        public record AnswerDto(UUID questionId, String answerContent) {}
    }

    public record StartSessionResponse(
            UUID sessionId,
            ExamClient.ExamDto exam,
            List<QuestionBankClient.QuestionResponse> questions
    ) {}
}

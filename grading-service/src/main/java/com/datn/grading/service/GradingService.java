package com.datn.grading.service;

import com.datn.grading.client.ExamSessionClient;
import com.datn.grading.client.QuestionBankClient;
import com.datn.grading.dto.SubmissionDto;
import com.datn.grading.entity.ExamResult;
import com.datn.grading.entity.QuestionGrade;
import com.datn.grading.repository.ExamResultRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class GradingService {

    private final ExamSessionClient sessionClient;
    private final QuestionBankClient questionClient;
    private final ExamResultRepository resultRepo;
    private final com.datn.grading.client.GeminiClient geminiClient;

    @RabbitListener(queues = "exam.submitted.queue")
    @Transactional
    public void processSubmittedExam(String message) {
        log.info("Nhận yêu cầu chấm điểm cho session: {}", message);
        try {
            UUID sessionId = UUID.fromString(message);

            // Tránh chấm lại
            if (resultRepo.findBySessionId(sessionId).isPresent()) {
                log.info("Session {} đã được chấm điểm trước đó", sessionId);
                return;
            }

            // 1. Fetch thông tin bài làm
            ExamSessionClient.SessionDetailDto sessionDetails = sessionClient.getSessionDetails(sessionId);
            
            if (sessionDetails.answers() == null || sessionDetails.answers().isEmpty()) {
                log.info("Thí sinh không làm câu nào, tự động 0 điểm.");
                saveEmptyResult(sessionId, sessionDetails.examId(), sessionDetails.userId());
                return;
            }

            // 2. Fetch thông tin câu hỏi & đáp án đúng
            List<UUID> questionIds = sessionDetails.answers().stream()
                    .map(ExamSessionClient.AnswerDto::questionId)
                    .toList();
            List<QuestionBankClient.QuestionDetailDto> questions = questionClient.getQuestionsWithAnswers(questionIds);
            Map<UUID, QuestionBankClient.QuestionDetailDto> questionMap = questions.stream()
                    .collect(Collectors.toMap(QuestionBankClient.QuestionDetailDto::id, q -> q));

            // 3. Tiến hành chấm điểm
            float totalScore = 0;
            ExamResult result = ExamResult.builder()
                    .sessionId(sessionId)
                    .examId(sessionDetails.examId())
                    .userId(sessionDetails.userId())
                    .status(ExamResult.Status.GRADED)
                    .build();

            for (ExamSessionClient.AnswerDto ans : sessionDetails.answers()) {
                QuestionBankClient.QuestionDetailDto qDetail = questionMap.get(ans.questionId());
                if (qDetail == null) continue;

                float score = 0;
                String feedback = null;

                if ("MULTIPLE_CHOICE".equals(qDetail.questionType())) {
                    // Tìm ID của option đúng
                    String correctOptionId = qDetail.options().stream()
                            .filter(QuestionBankClient.OptionDto::correct)
                            .map(o -> o.id().toString())
                            .findFirst().orElse("");

                    if (correctOptionId.equals(ans.answerContent())) {
                        score = 1.0f; // Mỗi câu trắc nghiệm 1 điểm
                    } else {
                        score = 0.0f;
                    }
                } else if ("ESSAY".equals(qDetail.questionType())) {
                    com.datn.grading.client.GeminiClient.GeminiGradeResponse aiGrade = 
                        geminiClient.gradeEssay(qDetail.content(), ans.answerContent());
                    score = aiGrade.score();
                    feedback = aiGrade.feedback();
                }

                totalScore += score;
                QuestionGrade grade = QuestionGrade.builder()
                        .examResult(result)
                        .questionId(ans.questionId())
                        .score(score)
                        .feedback(feedback)
                        .build();
                result.getQuestionGrades().add(grade);
            }

            result.setTotalScore(totalScore);
            resultRepo.save(result);
            log.info("Chấm điểm thành công cho session {}. Tổng điểm: {}", sessionId, totalScore);

        } catch (Exception e) {
            log.error("Lỗi khi chấm bài cho session {}", message, e);
        }
    }

    public Page<SubmissionDto.SummaryResponse> getSubmissions(UUID examId, String statusStr, Pageable pageable) {
        ExamResult.Status status = null;
        if (statusStr != null && !statusStr.isBlank()) {
            try {
                status = ExamResult.Status.valueOf(statusStr.toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }
        return resultRepo.searchResults(examId, status, pageable).map(this::toSummaryDto);
    }

    public SubmissionDto.DetailResponse getSubmissionDetail(UUID id) {
        ExamResult result = resultRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bài nộp: " + id));

        List<SubmissionDto.AnswerDetailDto> answerDtos = new ArrayList<>();
        
        // Fetch session answers & question details if possible
        try {
            ExamSessionClient.SessionDetailDto sessionDetails = sessionClient.getSessionDetails(result.getSessionId());
            if (sessionDetails != null && sessionDetails.answers() != null) {
                List<UUID> qIds = sessionDetails.answers().stream().map(ExamSessionClient.AnswerDto::questionId).toList();
                List<QuestionBankClient.QuestionDetailDto> qDetails = questionClient.getQuestionsWithAnswers(qIds);
                Map<UUID, QuestionBankClient.QuestionDetailDto> qMap = qDetails.stream().collect(Collectors.toMap(QuestionBankClient.QuestionDetailDto::id, q -> q));
                Map<UUID, QuestionGrade> gradeMap = result.getQuestionGrades().stream().collect(Collectors.toMap(QuestionGrade::getQuestionId, g -> g, (a, b) -> a));

                for (ExamSessionClient.AnswerDto ans : sessionDetails.answers()) {
                    QuestionBankClient.QuestionDetailDto q = qMap.get(ans.questionId());
                    QuestionGrade g = gradeMap.get(ans.questionId());
                    
                    String qContent = q != null ? q.content() : "Câu hỏi " + ans.questionId();
                    String qType = q != null ? q.questionType() : "MULTIPLE_CHOICE";
                    float earned = g != null ? g.getScore() : 0f;

                    answerDtos.add(new SubmissionDto.AnswerDetailDto(
                            ans.questionId(),
                            qContent,
                            qType,
                            1.0f,
                            ans.answerContent(),
                            ans.answerContent(),
                            ans.answerContent(),
                            earned > 0,
                            earned
                    ));
                }
            }
        } catch (Exception e) {
            log.warn("Không thể fetch chi tiết câu hỏi cho submission {}: {}", id, e.getMessage());
            for (QuestionGrade g : result.getQuestionGrades()) {
                answerDtos.add(new SubmissionDto.AnswerDetailDto(
                        g.getQuestionId(),
                        "Câu hỏi " + g.getQuestionId(),
                        "MULTIPLE_CHOICE",
                        1.0f,
                        null,
                        null,
                        null,
                        g.getScore() > 0,
                        g.getScore()
                ));
            }
        }

        return new SubmissionDto.DetailResponse(
                result.getId(),
                result.getExamId(),
                "Đề thi " + result.getExamId().toString().substring(0, 8),
                result.getUserId(),
                "Sinh viên " + result.getUserId().toString().substring(0, 6),
                "SV" + Math.abs(result.getUserId().hashCode() % 10000),
                result.getStatus().name(),
                result.getTotalScore(),
                10.0f,
                result.getGradedAt(),
                1800,
                null,
                answerDtos
        );
    }

    @Transactional
    public SubmissionDto.SummaryResponse gradeSubmission(UUID id, SubmissionDto.ManualGradeRequest req) {
        ExamResult result = resultRepo.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy bài nộp: " + id));

        if (req.score() != null) {
            result.setTotalScore(req.score());
        }
        result.setStatus(ExamResult.Status.GRADED);
        resultRepo.save(result);

        return toSummaryDto(result);
    }

    private SubmissionDto.SummaryResponse toSummaryDto(ExamResult r) {
        return new SubmissionDto.SummaryResponse(
                r.getId(),
                r.getExamId(),
                "Đề thi " + r.getExamId().toString().substring(0, 8),
                r.getUserId(),
                "Sinh viên " + r.getUserId().toString().substring(0, 6),
                "SV" + Math.abs(r.getUserId().hashCode() % 10000),
                r.getStatus().name(),
                r.getTotalScore(),
                10.0f,
                r.getGradedAt(),
                1800,
                null
        );
    }

    private void saveEmptyResult(UUID sessionId, UUID examId, UUID userId) {
        ExamResult result = ExamResult.builder()
                .sessionId(sessionId)
                .examId(examId)
                .userId(userId)
                .status(ExamResult.Status.GRADED)
                .totalScore(0f)
                .build();
        resultRepo.save(result);
    }
}

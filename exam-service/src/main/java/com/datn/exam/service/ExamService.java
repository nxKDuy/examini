package com.datn.exam.service;

import com.datn.exam.client.QuestionBankClient;
import com.datn.exam.dto.ExamDto;
import com.datn.exam.entity.Exam;
import com.datn.exam.repository.ExamRepository;
import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.Random;

@Service
@RequiredArgsConstructor
public class ExamService {

    private final ExamRepository repo;
    private final QuestionBankClient questionBankClient;

    public Page<ExamDto.Response> getExamsByCreator(UUID createdBy, Pageable pageable) {
        return repo.findByCreatedBy(createdBy, pageable).map(ExamDto.Response::from);
    }

    public ExamDto.Response getById(UUID id) {
        return ExamDto.Response.from(getOrThrow(id));
    }

    public ExamDto.Response getByCode(String code) {
        Exam e = repo.findByCode(code)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đề thi"));
        return ExamDto.Response.from(e);
    }

    @Transactional
    public ExamDto.Response createManual(ExamDto.ManualCreateRequest req, UUID createdBy) {
        // Resolve exam type
        Exam.ExamType examType;
        try {
            examType = req.type() != null ? Exam.ExamType.valueOf(req.type()) : Exam.ExamType.EXAM;
        } catch (IllegalArgumentException ex) {
            examType = Exam.ExamType.EXAM;
        }

        // Extract questionIds from weighted list
        List<UUID> questionIds = req.questions().stream()
                .map(ExamDto.QuestionWeightDto::questionId)
                .toList();

        Exam e = Exam.builder()
                .code(generateUniqueCode())
                .title(req.title())
                .description(req.description())
                .durationMinutes(req.durationMinutes())
                .startTime(req.startTime())
                .endTime(req.endTime())
                .status(Exam.Status.DRAFT)
                .type(examType)
                .passingScore(req.passingScore())
                .shuffleQuestions(req.shuffleQuestions())
                .shuffleOptions(req.shuffleOptions())
                .requireCam(req.requireCam())
                .requireMic(req.requireMic())
                .blockTabSwitch(req.blockTabSwitch())
                .requireFullscreen(req.requireFullscreen())
                .createdBy(createdBy)
                .questionIds(new ArrayList<>(questionIds))
                .build();
        return ExamDto.Response.from(repo.save(e));
    }

    @Transactional
    public ExamDto.Response autoGenerate(ExamDto.AutoGenerateRequest req, UUID createdBy) {
        List<UUID> questionIds = new ArrayList<>();

        try {
            if (req.easyCount() > 0) {
                questionIds.addAll(questionBankClient.getRandomQuestionIds(req.categoryId(), "EASY", req.easyCount()));
            }
            if (req.mediumCount() > 0) {
                questionIds.addAll(questionBankClient.getRandomQuestionIds(req.categoryId(), "MEDIUM", req.mediumCount()));
            }
            if (req.hardCount() > 0) {
                questionIds.addAll(questionBankClient.getRandomQuestionIds(req.categoryId(), "HARD", req.hardCount()));
            }
        } catch (FeignException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Lỗi khi lấy câu hỏi từ Question Bank: " + ex.getMessage());
        }

        if (questionIds.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Không có câu hỏi nào thỏa mãn tiêu chí");
        }

        // ponytail: Collections.shuffle IS Fisher-Yates — no need to reinvent
        Collections.shuffle(questionIds, new Random());

        Exam e = Exam.builder()
                .code(generateUniqueCode())
                .title(req.title())
                .description(req.description())
                .durationMinutes(req.durationMinutes())
                .startTime(req.startTime())
                .endTime(req.endTime())
                .status(Exam.Status.DRAFT)
                .createdBy(createdBy)
                .questionIds(questionIds)
                .build();
        return ExamDto.Response.from(repo.save(e));
    }

    @Transactional
    public ExamDto.Response publish(UUID id) {
        Exam e = getOrThrow(id);
        if (e.getQuestionIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Đề thi chưa có câu hỏi");
        }
        e.setStatus(Exam.Status.PUBLISHED);
        return ExamDto.Response.from(repo.save(e));
    }

    @Transactional
    public void delete(UUID id) {
        if (!repo.existsById(id)) throw notFound(id);
        repo.deleteById(id);
    }

    /**
     * Fisher-Yates shuffle cho một student dựa vào studentId (làm seed).
     * Đảm bảo cùng 1 student vào cùng 1 đề luôn ra cùng thứ tự câu hỏi.
     */
    public List<UUID> getShuffledQuestionsForStudent(UUID examId, UUID studentId) {
        Exam e = getOrThrow(examId);
        List<UUID> shuffled = new ArrayList<>(e.getQuestionIds());
        long seed = studentId.getMostSignificantBits() ^ studentId.getLeastSignificantBits();
        Collections.shuffle(shuffled, new Random(seed));
        return shuffled;
    }

    // ---- private helpers ----

    private Exam getOrThrow(UUID id) {
        return repo.findById(id).orElseThrow(() -> notFound(id));
    }

    private ResponseStatusException notFound(UUID id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Không tìm thấy đề thi: " + id);
    }

    private String generateUniqueCode() {
        String code;
        do {
            code = String.format("%06d", new Random().nextInt(999999));
        } while (repo.existsByCode(code));
        return code;
    }
}

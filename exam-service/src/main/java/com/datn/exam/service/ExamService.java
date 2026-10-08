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
    private final ExamCore core;
    private final ExamEventPublisher events;
    @org.springframework.beans.factory.annotation.Value("${question-bank.internal-token:}") private String questionToken="";

    public Page<ExamDto.Response> getExamsByCreator(UUID createdBy, Pageable pageable) {
        return repo.findVisible(createdBy, pageable).map(ExamDto.Response::from);
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
            throw ExamCore.error(400,"Unknown exam type");
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
                .startAt(req.startTime()==null?null:req.startTime().toInstant(java.time.ZoneOffset.UTC))
                .endAt(req.endTime()==null?null:req.endTime().toInstant(java.time.ZoneOffset.UTC))
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
        ScheduleRules.validate(e.getStartAt(),e.getEndAt(),e.getTimezone(),e.getDeadlineRule(),e.isAutoOpen(),e.isAutoClose(),e.getReminderOffsetsMinutes(),e.getDurationMinutes());
        if(e.getStartAt()!=null || e.getEndAt()!=null)e.setScheduleRevision(1);
        for(var q:req.questions()) {
            if(q.weight()<=0 || !Double.isFinite(q.weight()) || e.getPoints().put(q.questionId(),java.math.BigDecimal.valueOf(q.weight()))!=null)
                throw ExamCore.error(400,"Positive points and unique questions required");
        }
        repo.saveAndFlush(e);
        core.audit(e,createdBy,"EXAM_CREATED",null);
        if(e.getScheduleRevision()>0)events.scheduleChanged(e,"SCHEDULED",createdBy);
        return ExamDto.Response.from(e);
    }

    @Transactional
    public ExamDto.Response autoGenerate(ExamDto.AutoGenerateRequest req, UUID createdBy) {
        long requested=(long)req.easyCount()+req.mediumCount()+req.hardCount();
        if(requested<1 || requested>200)throw ExamCore.error(400,"Exam requires 1 to 200 questions");
        if(questionToken.isBlank())throw ExamCore.error(503,"Question Bank integration not configured");
        List<UUID> questionIds = new ArrayList<>();

        try {
            if (req.easyCount() > 0) {
                questionIds.addAll(questionBankClient.random(req.categoryId(), "EASY", req.easyCount(),questionToken,createdBy));
            }
            if (req.mediumCount() > 0) {
                questionIds.addAll(questionBankClient.random(req.categoryId(), "MEDIUM", req.mediumCount(),questionToken,createdBy));
            }
            if (req.hardCount() > 0) {
                questionIds.addAll(questionBankClient.random(req.categoryId(), "HARD", req.hardCount(),questionToken,createdBy));
            }
        } catch (FeignException ex) {
            throw ex; // Service advice preserves business status without leaking provider payloads.
        }

        if(questionIds.size()!=req.easyCount()+req.mediumCount()+req.hardCount() || new java.util.HashSet<>(questionIds).size()!=questionIds.size())
            throw ExamCore.error(409,"Insufficient approved questions for requested composition");
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
                .startAt(req.startTime()==null?null:req.startTime().toInstant(java.time.ZoneOffset.UTC))
                .endAt(req.endTime()==null?null:req.endTime().toInstant(java.time.ZoneOffset.UTC))
                .status(Exam.Status.DRAFT)
                .createdBy(createdBy)
                .questionIds(questionIds)
                .build();
        ScheduleRules.validate(e.getStartAt(),e.getEndAt(),e.getTimezone(),e.getDeadlineRule(),e.isAutoOpen(),e.isAutoClose(),e.getReminderOffsetsMinutes(),e.getDurationMinutes());
        if(e.getStartAt()!=null || e.getEndAt()!=null)e.setScheduleRevision(1);
        questionIds.forEach(q->e.getPoints().put(q,java.math.BigDecimal.ONE));
        repo.saveAndFlush(e);
        core.audit(e,createdBy,"EXAM_CREATED",null);
        if(e.getScheduleRevision()>0)events.scheduleChanged(e,"SCHEDULED",createdBy);
        return ExamDto.Response.from(e);
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

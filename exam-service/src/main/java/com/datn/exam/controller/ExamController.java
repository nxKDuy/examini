package com.datn.exam.controller;

import com.datn.exam.dto.ExamDto;
import com.datn.exam.service.ExamService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/exams")
@RequiredArgsConstructor
@Tag(name = "Exams", description = "Quản lý đề thi")
public class ExamController {

    private final ExamService service;

    @GetMapping
    @Operation(summary = "Lấy danh sách đề thi của người tạo")
    public Page<ExamDto.Response> getMyExams(
            @RequestHeader("X-User-Id") UUID userId,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return service.getExamsByCreator(userId, pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Lấy chi tiết đề thi theo ID")
    public ExamDto.Response getById(@PathVariable UUID id) {
        return service.getById(id);
    }

    @GetMapping("/code/{code}")
    @Operation(summary = "Lấy chi tiết đề thi theo mã tham gia")
    public ExamDto.Response getByCode(@PathVariable String code) {
        return service.getByCode(code);
    }

    @PostMapping("/manual")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo đề thi thủ công (chọn sẵn câu hỏi)")
    public ExamDto.Response createManual(
            @Valid @RequestBody ExamDto.ManualCreateRequest req,
            @RequestHeader("X-User-Id") UUID userId) {
        return service.createManual(req, userId);
    }

    @PostMapping("/auto")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo đề thi tự động ngẫu nhiên theo tiêu chí độ khó")
    public ExamDto.Response autoGenerate(
            @Valid @RequestBody ExamDto.AutoGenerateRequest req,
            @RequestHeader("X-User-Id") UUID userId) {
        return service.autoGenerate(req, userId);
    }

    @PutMapping("/{id}/publish")
    @Operation(summary = "Phát hành đề thi (thay đổi trạng thái sang PUBLISHED)")
    public ExamDto.Response publish(@PathVariable UUID id) {
        return service.publish(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Xóa đề thi")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @GetMapping("/{id}/questions/shuffled")
    @Operation(summary = "Lấy danh sách câu hỏi đã shuffle theo học sinh (dùng cho exam-session-service)")
    public List<UUID> getShuffledQuestionsForStudent(
            @PathVariable UUID id,
            @RequestParam UUID studentId) {
        return service.getShuffledQuestionsForStudent(id, studentId);
    }
}

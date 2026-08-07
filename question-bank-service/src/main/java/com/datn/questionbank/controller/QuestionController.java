package com.datn.questionbank.controller;

import com.datn.questionbank.dto.QuestionDto;
import com.datn.questionbank.entity.Question;
import com.datn.questionbank.service.QuestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/questions")
@RequiredArgsConstructor
@Tag(name = "Questions", description = "Quản lý câu hỏi")
public class QuestionController {

    private final QuestionService service;

    @GetMapping
    @Operation(summary = "Danh sách câu hỏi (phân trang, filter theo category/difficulty)")
    public Page<QuestionDto.QuestionResponse> findAll(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) Question.DifficultyLevel difficulty,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        return service.findAll(categoryId, difficulty, pageable);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết câu hỏi")
    public QuestionDto.QuestionResponse findById(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo câu hỏi mới")
    public QuestionDto.QuestionResponse create(
            @Valid @RequestBody QuestionDto.CreateRequest req,
            @RequestHeader("X-User-Id") UUID createdBy) {
        return service.create(req, createdBy);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Cập nhật câu hỏi")
    public QuestionDto.QuestionResponse update(
            @PathVariable UUID id,
            @RequestBody QuestionDto.UpdateRequest req,
            @RequestHeader("X-User-Id") UUID userId) {
        return service.update(id, req, userId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Xóa câu hỏi")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }

    @GetMapping("/random")
    @Operation(summary = "Lấy ngẫu nhiên danh sách ID câu hỏi (dùng cho service khác)")
    public java.util.List<UUID> getRandomQuestionIds(
            @RequestParam UUID categoryId,
            @RequestParam Question.DifficultyLevel difficulty,
            @RequestParam(defaultValue = "10") int limit) {
        return service.getRandomQuestionIds(categoryId, difficulty, limit);
    }

    @PostMapping("/batch")
    @Operation(summary = "Lấy chi tiết danh sách câu hỏi từ list IDs (dùng cho thi)")
    public java.util.List<QuestionDto.QuestionResponse> getByIds(@RequestBody java.util.List<UUID> ids) {
        return service.getByIds(ids);
    }

    @PostMapping(value = "/import/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Import câu hỏi từ file CSV",
            description = "Header: content,type,difficulty,source,option1,correct1,option2,correct2,...")
    public QuestionDto.ImportResult importCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam UUID categoryId,
            @RequestHeader("X-User-Id") UUID createdBy) {
        return service.importCsv(file, categoryId, createdBy);
    }
}

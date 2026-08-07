package com.datn.questionbank.controller;

import com.datn.questionbank.dto.SubjectDto;
import com.datn.questionbank.service.SubjectService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/subjects")
@RequiredArgsConstructor
@Tag(name = "Subjects", description = "Quản lý môn học")
public class SubjectController {

    private final SubjectService service;

    @GetMapping
    @Operation(summary = "Lấy danh sách tất cả môn học")
    public List<SubjectDto.Response> findAll() {
        return service.findAll();
    }

    @GetMapping("/{id}")
    @Operation(summary = "Lấy chi tiết môn học")
    public SubjectDto.Response findById(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo môn học mới")
    public SubjectDto.Response create(@Valid @RequestBody SubjectDto.CreateRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Cập nhật môn học")
    public SubjectDto.Response update(@PathVariable UUID id, @Valid @RequestBody SubjectDto.CreateRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Xóa môn học")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}

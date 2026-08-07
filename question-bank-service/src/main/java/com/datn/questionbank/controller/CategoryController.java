package com.datn.questionbank.controller;

import com.datn.questionbank.dto.CategoryDto;
import com.datn.questionbank.service.CategoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/categories")
@RequiredArgsConstructor
@Tag(name = "Categories", description = "Quản lý danh mục câu hỏi")
public class CategoryController {

    private final CategoryService service;

    @GetMapping
    @Operation(summary = "Lấy danh mục theo môn học")
    public List<CategoryDto.Response> findBySubject(@RequestParam UUID subjectId) {
        return service.findBySubject(subjectId);
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết danh mục")
    public CategoryDto.Response findById(@PathVariable UUID id) {
        return service.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo danh mục mới")
    public CategoryDto.Response create(@Valid @RequestBody CategoryDto.CreateRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Cập nhật danh mục")
    public CategoryDto.Response update(@PathVariable UUID id, @Valid @RequestBody CategoryDto.CreateRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Xóa danh mục")
    public void delete(@PathVariable UUID id) {
        service.delete(id);
    }
}

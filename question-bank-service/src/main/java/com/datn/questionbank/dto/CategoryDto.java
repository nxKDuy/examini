package com.datn.questionbank.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public class CategoryDto {

    public record CreateRequest(
            @NotNull UUID subjectId,
            @NotBlank @Size(max = 255) String name,
            UUID parentCategoryId     // null = danh mục gốc
    ) {}

    public record Response(
            UUID id,
            UUID subjectId,
            String subjectName,
            String name,
            UUID parentCategoryId
    ) {}
}

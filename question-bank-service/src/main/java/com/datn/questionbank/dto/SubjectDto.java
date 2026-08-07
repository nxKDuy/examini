package com.datn.questionbank.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public class SubjectDto {

    public record CreateRequest(
            @NotBlank @Size(max = 255) String name,
            String description
    ) {}

    public record Response(UUID id, String name, String description) {}
}

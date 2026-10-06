package com.datn.auth.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Contract nội bộ cho Notification: chỉ dữ liệu liên hệ tối thiểu, không role/quyền/token. */
public class ContactDto {

    public static final int MAX_USER_IDS = 100;

    public record ContactRequest(
            @NotNull(message = "userIds là bắt buộc")
            @Size(min = 1, max = MAX_USER_IDS, message = "userIds phải có từ 1 đến 100 phần tử")
            List<@NotNull(message = "userId không được null") UUID> userIds
    ) {}

    public record Contact(
            UUID userId,
            String email,
            String displayName
    ) {}
}

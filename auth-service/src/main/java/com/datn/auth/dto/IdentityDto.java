package com.datn.auth.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** Contract nội bộ Exam/Organization: định danh tối thiểu từ state Auth đã lưu, không role/quyền/token. */
public class IdentityDto {

    public static final int MAX_SELECTORS = 100;

    public record ResolveRequest(
            @Size(min = 1, max = MAX_SELECTORS, message = "userIds phải có từ 1 đến 100 phần tử")
            List<@NotNull(message = "userId không được null") UUID> userIds,
            @Size(min = 1, max = MAX_SELECTORS, message = "emails phải có từ 1 đến 100 phần tử")
            List<@NotBlank(message = "email không được rỗng") @Size(max = 255, message = "email quá dài") String> emails
    ) {
        @AssertTrue(message = "Chỉ được gửi đúng một trong userIds hoặc emails")
        public boolean isExactlyOneSelector() {
            return (userIds == null) != (emails == null);
        }
    }

    public record Identity(UUID userId, String displayName, String email, String persona) {}
}

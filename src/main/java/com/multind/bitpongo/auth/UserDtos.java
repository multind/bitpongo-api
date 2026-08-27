package com.multind.bitpongo.auth;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.util.Locale;

public final class UserDtos {
    private UserDtos() {}

    public record UserLoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password) {}

    public record UserCreateRequest(
            @NotBlank(message = "姓名不能为空") String name,
            @NotBlank(message = "邮箱不能为空") @Email(message = "邮箱格式错误") String email,
            @NotBlank(message = "密码不能为空")
            @Pattern(
                    regexp = "^(?=.*[A-Za-z])(?=.*\\d).{8,}$",
                    message = "密码至少8位，且必须同时包含字母和数字")
            String password) {
        public UserCreateRequest {
            if (email != null) {
                email = email.trim().toLowerCase(Locale.ROOT);
            }
        }
    }

    public record AccountDeletionRequest(
            @NotBlank(message = "密码不能为空") String password) {}

    public record UserInfo(long id, String name, String email) {}

    public record LoginData(String token, UserInfo info) {}

    public record UserResponse(
            long id,
            String name,
            String email,
            @JsonProperty("created_at") Instant createdAt) {}

    public enum DisplayTimeZoneMode { FOLLOW_DEVICE, FIXED }

    public record TimeZonePreference(
            DisplayTimeZoneMode mode,
            String timezone,
            @JsonProperty("effective_timezone") String effectiveTimezone) {}

    public record TimeZonePreferenceRequest(
            @NotNull DisplayTimeZoneMode mode,
            String timezone) {}

    public record DeviceTimeZoneRequest(
            @NotBlank String timezone) {}
}

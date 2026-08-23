package com.multind.bitpongo.notification;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.multind.bitpongo.auth.AuthenticatedUser;
import com.multind.bitpongo.common.api.ApiResponse;
import com.multind.bitpongo.common.api.BusinessException;
import java.time.LocalDateTime;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users/notifications/bark")
public class NotificationController {

    private final NotificationApplicationService notifications;

    public NotificationController(NotificationApplicationService notifications) {
        this.notifications = notifications;
    }

    public record BarkSettingRequest(
            String pushUrl,
            Boolean enabled,
            String locale,
            String timezone) {

        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new BusinessException(400, "请求包含不支持的字段");
        }
    }

    public record BarkTestRequest(String pushUrl) {

        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new BusinessException(400, "请求包含不支持的字段");
        }
    }

    public record BarkSettingResponse(
            boolean configured,
            boolean enabled,
            String maskedPushUrl,
            String locale,
            String timezone,
            LocalDateTime updatedAt) {
    }

    public record BarkTestResponse(boolean sent) {
    }

    @GetMapping
    public ApiResponse<BarkSettingResponse> get(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(response(notifications.getBarkSetting(user.id())));
    }

    @PutMapping
    public ApiResponse<BarkSettingResponse> update(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody BarkSettingRequest request) {
        UserBarkSettingService.SettingView setting = notifications.updateBarkSetting(
                user.id(),
                request.pushUrl(),
                request.enabled(),
                request.locale(),
                request.timezone());
        return ApiResponse.success(response(setting));
    }

    @DeleteMapping
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal AuthenticatedUser user) {
        notifications.deleteBarkSetting(user.id());
        return ApiResponse.success(null);
    }

    @PostMapping("/test")
    public ApiResponse<BarkTestResponse> test(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody(required = false) BarkTestRequest request) {
        String pushUrl = request == null ? null : request.pushUrl();
        return ApiResponse.success(new BarkTestResponse(
                notifications.testBark(user.id(), pushUrl)));
    }

    private static BarkSettingResponse response(UserBarkSettingService.SettingView setting) {
        return new BarkSettingResponse(
                setting.configured(),
                setting.enabled(),
                setting.maskedPushUrl(),
                setting.locale(),
                setting.timezone(),
                setting.updatedAt());
    }
}

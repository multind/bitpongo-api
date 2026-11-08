package com.multind.bitpongo.notification;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.multind.bitpongo.auth.AuthenticatedUser;
import com.multind.bitpongo.common.api.ApiResponse;
import com.multind.bitpongo.common.api.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

@Profile("!test")
@RestController
@RequestMapping("/api/users/notifications/channels")
public class NotificationChannelController {

    private final NotificationChannelService service;

    public NotificationChannelController(NotificationChannelService service) {
        this.service = service;
    }

    @JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
    public record Update(
            Boolean emailEnabled,
            Boolean messageEnabled,
            Boolean callEnabled,
            Boolean dingdingEnabled,
            Boolean feishuEnabled,
            Boolean telegramEnabled,
            Boolean confirmationEnabled) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new BusinessException(400, "请求包含不支持的字段");
        }
    }

    @PutMapping("/order")
    public ApiResponse<NotificationChannelService.Channels> reorder(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody java.util.List<String> order) {
        return ApiResponse.success(service.reorder(user.id(), order));
    }

    @GetMapping
    public ApiResponse<NotificationChannelService.Channels> get(
            @AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.get(user.id()));
    }

    @GetMapping("/strengths")
    public ApiResponse<java.util.Map<String, String>> strengths(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.strengths(user.id()));
    }

    @PutMapping("/strengths")
    public ApiResponse<java.util.Map<String, String>> strengths(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody java.util.Map<String, String> strengths) {
        return ApiResponse.success(service.updateStrengths(user.id(), strengths));
    }

    @PutMapping
    public ApiResponse<NotificationChannelService.Channels> update(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody Update request) {
        return ApiResponse.success(service.update(user.id(), new NotificationChannelService.Update(
                request == null ? null : request.emailEnabled(),
                request == null ? null : request.messageEnabled(),
                request == null ? null : request.callEnabled(),
                request == null ? null : request.dingdingEnabled(),
                request == null ? null : request.feishuEnabled(),
                request == null ? null : request.telegramEnabled(),
                request == null ? null : request.confirmationEnabled())));
    }
}

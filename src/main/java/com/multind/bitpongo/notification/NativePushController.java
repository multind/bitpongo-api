package com.multind.bitpongo.notification;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.multind.bitpongo.auth.AuthenticatedUser;
import com.multind.bitpongo.common.api.ApiResponse;
import com.multind.bitpongo.common.api.BusinessException;
import java.time.Instant;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@Profile("!test")
@RestController
@RequestMapping("/api/users/notifications/push")
public class NativePushController {
    private final NativePushService service;
    public NativePushController(NativePushService service) { this.service = service; }
    public record Settings(Boolean enabled, Boolean tradeSucceeded, Boolean tradeFailed,
            Boolean planAlerts, Boolean systemAlerts, Boolean soundEnabled, String locale, String timezone) {
        @JsonAnySetter public void unknown(String field, Object value) { throw bad(); }
    }
    public record SettingView(boolean available, boolean enabled, boolean tradeSucceeded, boolean tradeFailed,
            boolean planAlerts, boolean systemAlerts, boolean soundEnabled, String locale, String timezone, int deviceCount) {}
    public record Registration(String token, String platform, String permission, String appVersion, String locale, String timezone, Boolean activate) {
        public Registration(String token, String platform, String permission, String appVersion, String locale, String timezone) {
            this(token,platform,permission,appVersion,locale,timezone,false);
        }
        @JsonAnySetter public void unknown(String field, Object value) { throw bad(); }
        @Override public String toString() { return "Registration[token=<redacted>]"; }
    }
    public record DeviceView(String installationId, String platform, String appVersion, boolean enabled,
            Instant lastSeenAt, Instant createdAt) {}
    public record Registered(boolean registered) {}
    public record TestRequest(String installationId) {
        @JsonAnySetter public void unknown(String field, Object value) { throw bad(); }
    }
    public record Queued(boolean queued) {}
    public record LogoutRequest(String token) {
        @JsonAnySetter public void unknown(String field, Object value) { throw bad(); }
        @Override public String toString() { return "LogoutRequest[token=<redacted>]"; }
    }
    private static BusinessException bad() { return new BusinessException(400, "请求包含不支持的字段"); }
    @GetMapping public ApiResponse<SettingView> get(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.settings(user.id()));
    }
    @PutMapping public ApiResponse<SettingView> put(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody Settings body) {
        return ApiResponse.success(service.save(user.id(), body));
    }
    @GetMapping("/devices") public ApiResponse<List<DeviceView>> devices(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.devices(user.id()));
    }
    @PutMapping("/devices/{installationId}") public ApiResponse<Registered> register(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String installationId, @RequestBody Registration body) {
        return ApiResponse.success(new Registered(service.register(user.id(), installationId, body)));
    }
    @DeleteMapping("/devices/{installationId}") public ApiResponse<Void> delete(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String installationId) {
        service.remove(user.id(), installationId);
        return ApiResponse.success(null);
    }
    @PostMapping("/test") public ApiResponse<Queued> test(@AuthenticationPrincipal AuthenticatedUser user, @RequestBody TestRequest body) {
        service.test(user.id(), body.installationId());
        return ApiResponse.success(new Queued(true));
    }
    @PostMapping("/devices/{installationId}/logout") public ApiResponse<Void> logout(@AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable String installationId, @RequestBody LogoutRequest body) {
        service.logout(user.id(),installationId,body.token());
        return ApiResponse.success(null);
    }
}

package com.multind.bitpongo.alerts;

import com.multind.bitpongo.auth.AuthenticatedUser;
import com.multind.bitpongo.common.api.ApiResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@Profile("!test")
@RequestMapping("/api/alerts/logs")
public class AlertLogController {
    private final AlertEventService service;
    public AlertLogController(AlertEventService service) { this.service = service; }

    @GetMapping
    public ApiResponse<AlertDtos.Page<AlertDtos.Event>> list(@AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return ApiResponse.success(service.logs(user.id(), page, size));
    }
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        service.deleteLog(user.id(), id);
        return ApiResponse.success(null);
    }
    @DeleteMapping
    public ApiResponse<Void> clear(@AuthenticationPrincipal AuthenticatedUser user) {
        service.clearLogs(user.id());
        return ApiResponse.success(null);
    }
}

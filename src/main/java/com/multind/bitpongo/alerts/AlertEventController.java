package com.multind.bitpongo.alerts;

import com.multind.bitpongo.auth.AuthenticatedUser;
import com.multind.bitpongo.common.api.ApiResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("!test") @RequestMapping("/api/alerts/events")
public class AlertEventController {
    private final AlertEventService service;
    public AlertEventController(AlertEventService service) { this.service = service; }
    @GetMapping public ApiResponse<AlertDtos.Page<AlertDtos.Event>> list(@AuthenticationPrincipal AuthenticatedUser u,
            @RequestParam(defaultValue="false") boolean unreadOnly, @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size) {
        return ApiResponse.success(service.list(u.id(),unreadOnly,page,size));
    }
    @GetMapping("/unread") public ApiResponse<Long> unread(@AuthenticationPrincipal AuthenticatedUser u) { return ApiResponse.success(service.unread(u.id())); }
    @PutMapping("/{id}/read") public ApiResponse<Void> read(@AuthenticationPrincipal AuthenticatedUser u, @PathVariable long id) { service.read(u.id(),id); return ApiResponse.success(null); }
    @PutMapping("/read-all") public ApiResponse<Void> readAll(@AuthenticationPrincipal AuthenticatedUser u) { service.readAll(u.id()); return ApiResponse.success(null); }
    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@AuthenticationPrincipal AuthenticatedUser u, @PathVariable long id) { service.delete(u.id(),id); return ApiResponse.success(null); }
    @DeleteMapping public ApiResponse<Void> clear(@AuthenticationPrincipal AuthenticatedUser u) { service.clear(u.id()); return ApiResponse.success(null); }
}

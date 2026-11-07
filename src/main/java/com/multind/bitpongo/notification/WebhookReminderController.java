package com.multind.bitpongo.notification;

import com.multind.bitpongo.auth.AuthenticatedUser;
import com.multind.bitpongo.common.api.ApiResponse;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/notifications/webhook-destinations")
class WebhookReminderController {
    private final WebhookReminderService service;

    WebhookReminderController(WebhookReminderService service) { this.service = service; }

    @GetMapping
    ApiResponse<WebhookReminderService.Settings> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.list(user.id()));
    }

    @PostMapping
    ApiResponse<Map<String, Long>> create(@AuthenticationPrincipal AuthenticatedUser user,
                                         @RequestBody WebhookReminderService.Draft draft) {
        return ApiResponse.success(Map.of("id", service.create(user.id(), draft)));
    }

    @PutMapping("/{id}")
    ApiResponse<Void> update(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id,
                             @RequestBody WebhookReminderService.Draft draft) {
        service.update(user.id(), id, draft);
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{id}")
    ApiResponse<Void> delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        service.delete(user.id(), id);
        return ApiResponse.success(null);
    }

    @PostMapping("/{id}/test")
    ApiResponse<Void> test(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable long id) {
        service.test(user.id(), id);
        return ApiResponse.success(null);
    }
}

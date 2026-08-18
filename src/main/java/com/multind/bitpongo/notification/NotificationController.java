package com.multind.bitpongo.notification;

import com.multind.bitpongo.common.api.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
public class NotificationController {
    private final NotificationApplicationService notifications;
    public NotificationController(NotificationApplicationService notifications) { this.notifications = notifications; }

    public record DingRequest(@NotBlank String webhook, @NotBlank String signed) {}

    @PostMapping("/ding")
    public ApiResponse<Map<String, Object>> ding(@Valid @RequestBody DingRequest request) {
        return ApiResponse.success(notifications.testDing(request.webhook(), request.signed()));
    }

    @GetMapping("/notices")
    public ApiResponse<String> notices() { return ApiResponse.success(notifications.notices()); }
}

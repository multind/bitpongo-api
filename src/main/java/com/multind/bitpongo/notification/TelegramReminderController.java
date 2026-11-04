package com.multind.bitpongo.notification;

import com.multind.bitpongo.auth.AuthenticatedUser;
import com.multind.bitpongo.common.api.ApiResponse;
import com.multind.bitpongo.common.api.BusinessException;
import java.util.Map;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.JsonNode;

@RestController @Profile("!test")
class TelegramReminderController {
    private static final String SETTINGS = "/api/users/notifications/telegram";
    private final TelegramReminderService service;
    private final TelegramClient client;

    TelegramReminderController(TelegramReminderService service, TelegramClient client) {
        this.service = service;
        this.client = client;
    }

    @GetMapping(SETTINGS)
    ApiResponse<TelegramReminderService.Status> status(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.status(user.id()));
    }

    @PostMapping(SETTINGS + "/bind")
    ApiResponse<Map<String, String>> bind(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(Map.of("url", service.start(user.id())));
    }

    @PostMapping(SETTINGS + "/test")
    ApiResponse<Void> test(@AuthenticationPrincipal AuthenticatedUser user) {
        service.test(user.id());
        return ApiResponse.success(null);
    }

    @DeleteMapping(SETTINGS)
    ApiResponse<Void> unlink(@AuthenticationPrincipal AuthenticatedUser user) {
        service.unlink(user.id());
        return ApiResponse.success(null);
    }

    @PostMapping("/api/integrations/telegram/webhook")
    void webhook(@RequestHeader(value="X-Telegram-Bot-Api-Secret-Token", required=false) String secret, @RequestBody JsonNode update) {
        if (!client.authentic(secret)) throw new BusinessException(403, "Invalid Telegram webhook secret");
        var message = update.path("message");
        var chat = message.path("chat");
        long id = chat.path("id").asLong(0);
        String text = message.path("text").asText("");
        if ("private".equals(chat.path("type").asText("")) && id > 0 && id == message.path("from").path("id").asLong(0)
                && text.matches("/start [A-Za-z0-9_-]{32}")) service.bind(text.substring(7), id);
    }
}

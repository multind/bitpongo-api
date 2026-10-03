package com.multind.bitpongo.alerts;

import com.multind.bitpongo.auth.AuthenticatedUser;
import com.multind.bitpongo.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("!test") @RequestMapping("/api/webhooks")
public class WebhookController {
    private final WebhookService hooks;
    public WebhookController(WebhookService hooks) { this.hooks=hooks; }
    @GetMapping public ApiResponse<?> list(@AuthenticationPrincipal AuthenticatedUser user) { return ApiResponse.success(hooks.list(user.id())); }
    @GetMapping("/available") public ApiResponse<Boolean> available() { return ApiResponse.success(hooks.available()); }
    @PostMapping public ApiResponse<Long> create(@AuthenticationPrincipal AuthenticatedUser user,@RequestBody WebhookService.Draft body) { return ApiResponse.success(hooks.create(user.id(),body)); }
    @PutMapping("/{id}") public ApiResponse<Void> update(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable long id,@RequestBody WebhookService.Draft body) { hooks.update(user.id(),id,body);return ApiResponse.success(null); }
    @PostMapping("/{id}/activate") public ApiResponse<WebhookService.Activation> activate(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable long id) { return ApiResponse.success(hooks.activate(user.id(),id)); }
    @DeleteMapping("/{id}") public ApiResponse<Void> revoke(@AuthenticationPrincipal AuthenticatedUser user,@PathVariable long id) { hooks.revoke(user.id(),id);return ApiResponse.success(null); }
    @PostMapping("/receive/{publicId}") public ApiResponse<Void> receive(@PathVariable String publicId,HttpServletRequest request) throws java.io.IOException {
        hooks.receive(publicId,request.getHeader("X-Webhook-Timestamp"),request.getHeader("X-Webhook-Nonce"),request.getHeader("X-Webhook-Signature"),request.getInputStream().readNBytes(4097),request.getRemoteAddr());
        return ApiResponse.success(null);
    }
}

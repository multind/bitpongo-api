package com.multind.bitpongo.alerts;

import com.multind.bitpongo.auth.AuthenticatedUser;
import com.multind.bitpongo.common.api.ApiResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController @Profile("!test") @RequestMapping("/api/strategy-subscriptions/fear-greed")
public class StrategySubscriptionController {
    private final StrategySubscriptionService service;
    public StrategySubscriptionController(StrategySubscriptionService service) { this.service=service; }
    @GetMapping public ApiResponse<StrategySubscriptionService.Settings> get(@AuthenticationPrincipal AuthenticatedUser user) { return ApiResponse.success(service.get(user.id())); }
    @PutMapping public ApiResponse<Void> save(@AuthenticationPrincipal AuthenticatedUser user,@RequestBody StrategySubscriptionService.Settings settings) { service.save(user.id(),settings);return ApiResponse.success(null); }
    @DeleteMapping public ApiResponse<Void> delete(@AuthenticationPrincipal AuthenticatedUser user) { service.delete(user.id());return ApiResponse.success(null); }
}

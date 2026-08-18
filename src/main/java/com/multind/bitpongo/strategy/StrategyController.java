package com.multind.bitpongo.strategy;

import com.multind.bitpongo.auth.AuthenticatedUser;
import com.multind.bitpongo.common.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.multind.bitpongo.strategy.StrategyDtos.StrategyCreateRequest;
import static com.multind.bitpongo.strategy.StrategyDtos.StrategyCreatedData;

@RestController
@RequestMapping("/api/strategies")
public class StrategyController {
    private final StrategyApplicationService strategies;

    public StrategyController(StrategyApplicationService strategies) {
        this.strategies = strategies;
    }

    @PostMapping("/create")
    public ApiResponse<StrategyCreatedData> create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @Valid @RequestBody StrategyCreateRequest request) {
        return ApiResponse.success(strategies.create(user.id(), request));
    }

    @GetMapping("/list/active")
    public ApiResponse<List<StrategyEntity>> active(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(strategies.active(user.id()));
    }
}

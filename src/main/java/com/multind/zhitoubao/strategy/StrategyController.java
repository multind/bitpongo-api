package com.multind.zhitoubao.strategy;

import com.multind.zhitoubao.auth.AuthenticatedUser;
import com.multind.zhitoubao.common.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static com.multind.zhitoubao.strategy.StrategyDtos.StrategyCreateRequest;
import static com.multind.zhitoubao.strategy.StrategyDtos.StrategyCreatedData;

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

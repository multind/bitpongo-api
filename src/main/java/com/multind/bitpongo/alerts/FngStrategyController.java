package com.multind.bitpongo.alerts;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.multind.bitpongo.auth.AuthenticatedUser;
import com.multind.bitpongo.common.api.ApiResponse;
import com.multind.bitpongo.common.api.BusinessException;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Profile("!test")
@RestController
@RequestMapping("/api/fng-strategies")
public class FngStrategyController {
    private final FngStrategyService service;
    private final FngPositionService positions;

    public FngStrategyController(FngStrategyService service, FngPositionService positions) {
        this.service = service;
        this.positions = positions;
    }

    public record Enabled(boolean enabled) {
        @JsonAnySetter
        public void rejectUnknownField(String field, Object value) {
            throw new BusinessException(400, "请求包含不支持的字段");
        }
    }

    @PostMapping
    public ApiResponse<Long> create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody FngStrategyDtos.Create request) {
        return ApiResponse.success(service.create(user.id(), request));
    }

    @GetMapping
    public ApiResponse<List<FngStrategyDtos.View>> list(@AuthenticationPrincipal AuthenticatedUser user) {
        return ApiResponse.success(service.list(user.id()));
    }

    @GetMapping("/page")
    public ApiResponse<FngStrategyService.StrategyPage> page(@AuthenticationPrincipal AuthenticatedUser user, @org.springframework.web.bind.annotation.RequestParam(defaultValue="0") int page, @org.springframework.web.bind.annotation.RequestParam(defaultValue="10") int size) {
        return ApiResponse.success(service.page(user.id(),page,size));
    }

    @GetMapping("/{id}")
    public ApiResponse<FngStrategyDtos.View> detail(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long id) {
        return ApiResponse.success(service.detail(user.id(), id));
    }

    @GetMapping("/archive")
    public ApiResponse<FngStrategyService.StrategyPage> archive(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.success(service.archive(user.id(), page, size));
    }

    @GetMapping("/{id}/positions")
    public ApiResponse<FngStrategyDtos.PositionPage> positions(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(positions.positions(user.id(), id, page, size));
    }

    @GetMapping("/{id}/executions")
    public ApiResponse<FngStrategyDtos.ExecutionPage> executions(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long id,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success(positions.executions(user.id(), id, page, size));
    }

    @GetMapping("/{id}/performance")
    public ApiResponse<FngStrategyDtos.Performance> performance(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long id) {
        return ApiResponse.success(positions.performance(user.id(), id));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long id,
            @RequestBody FngStrategyDtos.Create request) {
        service.update(user.id(), id, request);
        return ApiResponse.success(null);
    }

    @PutMapping("/{id}/enabled")
    public ApiResponse<Void> enabled(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long id,
            @RequestBody Enabled request) {
        service.enabled(user.id(), id, request.enabled());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long id) {
        service.delete(user.id(), id);
        return ApiResponse.success(null);
    }
}

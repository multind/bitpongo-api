package com.multind.bitpongo.exchange;

import com.multind.bitpongo.auth.AuthenticatedUser;
import com.multind.bitpongo.common.api.ApiResponse;
import java.math.BigDecimal;
import java.util.List;
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

import static com.multind.bitpongo.exchange.ExchangeDtos.BalanceView;
import static com.multind.bitpongo.exchange.ExchangeDtos.ExchangeCheckRequest;
import static com.multind.bitpongo.exchange.ExchangeDtos.ExchangeUpsertRequest;
import static com.multind.bitpongo.exchange.ExchangeDtos.ExchangeView;
import static com.multind.bitpongo.exchange.ExchangeDtos.MinimumAmountRequest;

@RestController
@RequestMapping("/api/exchanges")
public class ExchangeController {
    private final ExchangeApplicationService exchanges;

    public ExchangeController(ExchangeApplicationService exchanges) {
        this.exchanges = exchanges;
    }

    @GetMapping("/list")
    public ApiResponse<List<ExchangeView>> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(defaultValue = "0") int skip,
            @RequestParam(defaultValue = "100") int limit) {
        return ApiResponse.success(exchanges.list(user.id(), skip, limit));
    }

    @GetMapping("/{exchangeId}")
    public ApiResponse<ExchangeView> detail(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long exchangeId) {
        return ApiResponse.success(exchanges.detail(user.id(), exchangeId));
    }

    @PostMapping("/create")
    public ApiResponse<ExchangeView> create(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody ExchangeUpsertRequest request) {
        return ApiResponse.success(exchanges.create(user.id(), request));
    }

    @PutMapping("/{exchangeId}")
    public ApiResponse<ExchangeView> update(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long exchangeId,
            @RequestBody ExchangeUpsertRequest request) {
        return ApiResponse.success(exchanges.update(user.id(), exchangeId, request));
    }

    @DeleteMapping("/{exchangeId}")
    public ApiResponse<Void> delete(
            @AuthenticationPrincipal AuthenticatedUser user,
            @PathVariable long exchangeId) {
        exchanges.delete(user.id(), exchangeId);
        return ApiResponse.success(null);
    }

    @PostMapping("/check")
    public ApiResponse<BalanceView> check(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody ExchangeCheckRequest request) {
        return new ApiResponse<>(200, "API密钥验证成功", exchanges.check(user.id(), request));
    }

    @PostMapping("/minimumAmount")
    public ApiResponse<BigDecimal> minimumAmount(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestBody MinimumAmountRequest request) {
        return ApiResponse.success(exchanges.minimumAmount(user.id(), request));
    }
}

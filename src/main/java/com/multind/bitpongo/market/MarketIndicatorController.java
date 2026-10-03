package com.multind.bitpongo.market;

import com.multind.bitpongo.common.api.ApiResponse;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/market/indicators")
public class MarketIndicatorController {
    private final FearGreedService fearGreed;
    public MarketIndicatorController(FearGreedService fearGreed) { this.fearGreed=fearGreed; }
    @GetMapping("/fear-greed")
    public ApiResponse<FearGreedService.History> history(@RequestParam(defaultValue="30") int limit) {
        return ApiResponse.success(fearGreed.history(limit));
    }
}

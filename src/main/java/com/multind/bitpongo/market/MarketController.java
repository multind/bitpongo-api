package com.multind.bitpongo.market;

import com.multind.bitpongo.common.api.ApiResponse;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/market")
public class MarketController {
    private final MarketDataService service;
    public MarketController(MarketDataService service) { this.service = service; }
    @GetMapping("/symbols") public ApiResponse<List<MarketDtos.Symbol>> symbols(@RequestParam(defaultValue = "") String q) {
        return ApiResponse.success(service.symbols(q));
    }
    @GetMapping("/tickers") public ApiResponse<List<MarketDtos.Ticker>> tickers() { return ApiResponse.success(service.tickers()); }
    @GetMapping("/candles") public ApiResponse<List<MarketDtos.Candle>> candles(@RequestParam String symbol,
            @RequestParam(defaultValue = "1h") String interval, @RequestParam(defaultValue = "120") int limit) {
        return ApiResponse.success(service.candles(symbol, interval, limit));
    }
}

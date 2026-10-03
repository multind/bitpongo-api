package com.multind.bitpongo.market;
import java.util.List;

public interface MarketDataClient {
    List<MarketDtos.Symbol> symbols();
    List<MarketDtos.Ticker> tickers();
    List<MarketDtos.Candle> candles(String symbol, String interval, int limit);
}

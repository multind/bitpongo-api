package com.multind.zhitoubao.market;

import java.util.function.Consumer;

public interface BinanceMarketStreamClient {
    StreamHandle connect(
            Consumer<TickerEvent> onTicker,
            Consumer<Throwable> onFailure,
            Runnable onClosed);
}

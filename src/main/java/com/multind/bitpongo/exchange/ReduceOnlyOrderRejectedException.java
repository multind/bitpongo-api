package com.multind.bitpongo.exchange;

/** Binance definitively rejected a reduce-only order; this is not an ambiguous submission. */
public class ReduceOnlyOrderRejectedException extends RuntimeException {
    private final int exchangeCode;

    public ReduceOnlyOrderRejectedException(int exchangeCode) {
        super("Reduce-only order rejected: " + exchangeCode);
        this.exchangeCode = exchangeCode;
    }

    public int exchangeCode() {
        return exchangeCode;
    }
}

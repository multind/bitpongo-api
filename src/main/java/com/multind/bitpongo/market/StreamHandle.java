package com.multind.bitpongo.market;

@FunctionalInterface
public interface StreamHandle extends AutoCloseable {
    @Override
    void close();
}

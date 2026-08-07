package com.multind.zhitoubao.market;

@FunctionalInterface
public interface StreamHandle extends AutoCloseable {
    @Override
    void close();
}

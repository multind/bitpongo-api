package com.multind.bitpongo.scheduler;

public interface AssetSnapshotUseCase {
    void captureAll();
    void capture(long planId);
}

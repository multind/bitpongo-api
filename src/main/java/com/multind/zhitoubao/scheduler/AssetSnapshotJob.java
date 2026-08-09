package com.multind.zhitoubao.scheduler;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

@DisallowConcurrentExecution
public class AssetSnapshotJob implements Job {
    @Autowired private AssetSnapshotUseCase snapshots;

    @Override
    public void execute(JobExecutionContext context) {
        snapshots.captureAll();
    }
}

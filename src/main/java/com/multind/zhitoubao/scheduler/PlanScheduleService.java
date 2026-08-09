package com.multind.zhitoubao.scheduler;

public interface PlanScheduleService {
    void schedule(long planId, String cron);
    void pause(long planId);
    void resume(long planId, String cron);
    void remove(long planId);
}

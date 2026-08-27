package com.multind.bitpongo.scheduler;

import java.time.ZoneId;

public interface PlanScheduleService {
    void schedule(long planId, String cron, ZoneId zone);
    void pause(long planId);
    void resume(long planId, String cron, ZoneId zone);
    void remove(long planId);
}

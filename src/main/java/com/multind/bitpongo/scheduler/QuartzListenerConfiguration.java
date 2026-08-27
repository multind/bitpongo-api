package com.multind.bitpongo.scheduler;

import org.springframework.boot.quartz.autoconfigure.SchedulerFactoryBeanCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class QuartzListenerConfiguration {

    @Bean
    SchedulerFactoryBeanCustomizer planMisfireMetricsCustomizer(PlanMisfireTriggerListener listener) {
        return schedulerFactory -> schedulerFactory.setGlobalTriggerListeners(listener);
    }
}

package com.opayque.api.infrastructure.config;

import io.micrometer.core.aop.TimedAspect;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Infrastructure configuration to enable Micrometer's @Timed annotation support.
 * This allows us to track latency and throughput on specific service methods
 * without introducing manual stopwatch logic or breaking existing test mocks.
 */
@Configuration
public class MonitoringConfig {

    @Bean
    public TimedAspect timedAspect(MeterRegistry registry) {
        return new TimedAspect(registry);
    }
}
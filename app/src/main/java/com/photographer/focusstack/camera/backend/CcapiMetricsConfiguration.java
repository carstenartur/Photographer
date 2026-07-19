package com.photographer.focusstack.camera.backend;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Uses an externally configured registry when present and a local registry otherwise. */
@Configuration(proxyBeanMethods = false)
class CcapiMetricsConfiguration {

    @Bean
    @ConditionalOnMissingBean(MeterRegistry.class)
    MeterRegistry ccapiMeterRegistry() {
        return new SimpleMeterRegistry();
    }
}

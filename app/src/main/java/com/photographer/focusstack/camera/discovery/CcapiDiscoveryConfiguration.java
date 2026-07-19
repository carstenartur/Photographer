package com.photographer.focusstack.camera.discovery;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Spring wiring for the otherwise platform- and framework-independent discovery core. */
@Configuration(proxyBeanMethods = false)
public class CcapiDiscoveryConfiguration {

    @Bean
    public CcapiDiscoveryService ccapiDiscoveryService() {
        return new CcapiDiscoveryService();
    }
}

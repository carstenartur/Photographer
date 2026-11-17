package com.photographer.focusstack.fresnel;

import com.photographer.focusstack.camera.integration.AbstractPostgresIntegrationTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Runs the same paired HTTP workflow against the repository's shared PostgreSQL container. */
class FresnelPairingPostgresIT extends PairedHttpAcceptance {

    @DynamicPropertySource
    static void postgres(DynamicPropertyRegistry properties) {
        AbstractPostgresIntegrationTest.configureProperties(properties);
    }
}

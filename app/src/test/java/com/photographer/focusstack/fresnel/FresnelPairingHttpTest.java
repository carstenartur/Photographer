package com.photographer.focusstack.fresnel;

import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "spring.datasource.url=jdbc:h2:mem:fresnel-pair-http")
class FresnelPairingHttpTest extends PairedHttpAcceptance {}

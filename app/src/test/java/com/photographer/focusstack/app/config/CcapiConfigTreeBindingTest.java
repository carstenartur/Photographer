package com.photographer.focusstack.app.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CcapiConfigTreeBindingTest {

    private static final String PROFILE_ID = "camera-smoke";

    @Test
    void bindsUsernameAndPasswordFromRuntimeOnlyConfigTree(@TempDir Path secrets)
            throws Exception {
        String username = "runtime-user-sentinel";
        String password = "runtime-password-sentinel";
        Files.writeString(secrets.resolve(
                "focusstack.camera.ccapi.credentials.camera-smoke.username"), username);
        Files.writeString(secrets.resolve(
                "focusstack.camera.ccapi.credentials.camera-smoke.password"), password);

        String configTree = secrets.toAbsolutePath().normalize() + "/";
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(TestConfiguration.class)
                .withSystemProperties(
                        "spring.config.import=configtree:" + configTree)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    CcapiAuthenticationProperties properties =
                            context.getBean(CcapiAuthenticationProperties.class);
                    try (CcapiAuthenticationProperties.CredentialSnapshot snapshot =
                                 properties.snapshot(PROFILE_ID)) {
                        CcapiAuthenticationProperties.ResolvedCredentials resolved =
                                snapshot.credentials().orElseThrow();
                        assertThat(resolved.username()).isEqualTo(username);
                        assertThat(resolved.passwordCopy())
                                .containsExactly(password.toCharArray());
                        assertThat(snapshot.toString())
                                .doesNotContain(username)
                                .doesNotContain(password);
                    }
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(CcapiAuthenticationProperties.class)
    static class TestConfiguration {
    }
}

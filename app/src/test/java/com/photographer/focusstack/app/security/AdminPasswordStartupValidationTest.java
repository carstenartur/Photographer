package com.photographer.focusstack.app.security;

import com.photographer.focusstack.FocusStackApplication;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.util.Throwables.getRootCause;

@SpringBootTest(
        classes = FocusStackApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "focusstack.security.admin-password=StrongTestPassword!42",
                "server.port=0"
        })
class AdminPasswordStartupValidationTest {

    @Test
    void contextStartsWhenAdminPasswordIsConfigured() {
        // Context boot succeeds due @SpringBootTest with a non-default password.
    }

    @Test
    void startupFailsWhenAdminPasswordIsMissing() {
        Throwable thrown = assertThatThrownBy(() -> {
            try (ConfigurableApplicationContext ignored = new SpringApplicationBuilder(FocusStackApplication.class)
                    .web(WebApplicationType.SERVLET)
                    .run("--focusstack.security.admin-password=", "--server.port=0")) {
                // no-op
            }
        }).isInstanceOf(IllegalStateException.class).actual();
        Throwable rootCause = getRootCause(thrown);
        Throwable messageSource = rootCause != null ? rootCause : thrown;
        assertThat(messageSource.getMessage())
                .contains("FOCUSSTACK_SECURITY_ADMIN_PASSWORD is required");
    }
}

package com.photographer.focusstack.app.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Fails packaged startup when an explicitly required CCAPI profile has no
 * complete externally bound credential pair. No profile id or secret is logged.
 */
@Component
public final class CcapiCredentialStartupValidator implements ApplicationRunner {

    private final CcapiAuthenticationProperties authentication;
    private final CcapiRuntimeProperties runtime;

    public CcapiCredentialStartupValidator(
            CcapiAuthenticationProperties authentication,
            CcapiRuntimeProperties runtime) {
        this.authentication = Objects.requireNonNull(authentication, "authentication");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public void run(ApplicationArguments args) {
        Set<String> required = new LinkedHashSet<>();
        for (String profileId : runtime.getRequiredProfileIds()) {
            if (profileId == null || profileId.isBlank()) {
                throw new IllegalStateException(
                        "Required CCAPI profile identifiers must not be blank");
            }
            required.add(profileId);
        }

        long missing = required.stream()
                .filter(profileId -> !authentication.state(profileId)
                        .credentialsConfigured())
                .count();
        if (missing != 0) {
            throw new IllegalStateException(
                    "Required CCAPI credentials are missing for "
                            + missing + " configured profile(s)");
        }
    }
}

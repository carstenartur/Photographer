package com.photographer.focusstack.app.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CcapiCredentialStartupValidatorTest {

    @Test
    void acceptsNoRequirementsAndCompleteExternalCredentials() {
        CcapiAuthenticationProperties authentication =
                new CcapiAuthenticationProperties();
        CcapiRuntimeProperties runtime = new CcapiRuntimeProperties();
        CcapiCredentialStartupValidator validator =
                new CcapiCredentialStartupValidator(authentication, runtime);

        assertThatCode(() -> validator.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();

        authentication.setCredentials(Map.of(
                "camera-a", credential("runtime-user", "runtime-password")));
        runtime.setRequiredProfileIds(List.of("camera-a", "camera-a"));

        assertThatCode(() -> validator.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    @Test
    void reportsOnlyMissingCountWithoutProfileOrSecret() {
        CcapiAuthenticationProperties authentication =
                new CcapiAuthenticationProperties();
        CcapiRuntimeProperties runtime = new CcapiRuntimeProperties();
        runtime.setRequiredProfileIds(List.of("private-camera-a", "private-camera-b"));
        CcapiCredentialStartupValidator validator =
                new CcapiCredentialStartupValidator(authentication, runtime);

        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 configured profile(s)")
                .hasMessageNotContaining("private-camera-a")
                .hasMessageNotContaining("private-camera-b");
    }

    @Test
    void incompleteCredentialMountIsCountedWithoutLeakingProfileOrUsername() {
        String privateProfile = "private-camera-partial";
        String visibleUsername = "must-not-leak-user";
        CcapiAuthenticationProperties authentication =
                new CcapiAuthenticationProperties();
        authentication.setCredentials(Map.of(
                privateProfile, credential(visibleUsername, null)));
        CcapiRuntimeProperties runtime = new CcapiRuntimeProperties();
        runtime.setRequiredProfileIds(List.of(privateProfile));
        CcapiCredentialStartupValidator validator =
                new CcapiCredentialStartupValidator(authentication, runtime);

        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("1 configured profile(s)")
                .hasMessageNotContaining(privateProfile)
                .hasMessageNotContaining(visibleUsername)
                .hasNoCause();
    }

    @Test
    void rejectsBlankRequirementWithoutInspectingCredentials() {
        CcapiRuntimeProperties runtime = new CcapiRuntimeProperties();
        runtime.setRequiredProfileIds(List.of(" "));
        CcapiCredentialStartupValidator validator =
                new CcapiCredentialStartupValidator(
                        new CcapiAuthenticationProperties(), runtime);

        assertThatThrownBy(() -> validator.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be blank");
    }

    private static CcapiAuthenticationProperties.Credential credential(
            String username,
            String password) {
        CcapiAuthenticationProperties.Credential credential =
                new CcapiAuthenticationProperties.Credential();
        credential.setUsername(username);
        credential.setPassword(password);
        return credential;
    }
}

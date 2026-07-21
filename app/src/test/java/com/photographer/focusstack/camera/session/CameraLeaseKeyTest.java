package com.photographer.focusstack.camera.session;

import com.photographer.focusstack.common.model.CameraProfile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

class CameraLeaseKeyTest {

    @Test
    void normalizesImplicitCcapiLocationToApplicationDefaultPort() {
        CameraLeaseKey key = CameraLeaseKey.fromCcapiLocation(" Camera.Example ");

        assertThat(key.canonicalOriginForTesting())
                .isEqualTo("http://camera.example:8080");
    }

    @Test
    void discardsPathQueryFragmentAndNormalizesExplicitDefaultPort() {
        CameraLeaseKey first = CameraLeaseKey.fromCcapiLocation(
                "HTTP://Camera.Example/ccapi/ver100?debug=true#fragment");
        CameraLeaseKey second = CameraLeaseKey.fromCcapiLocation(
                "http://camera.example:80/other");

        assertThat(first).isEqualTo(second);
        assertThat(first.canonicalOriginForTesting())
                .isEqualTo("http://camera.example:80");
    }

    @Test
    void mapsDifferentProfilesForOneOriginToOnePrivateKey() {
        CameraProfile first = profile("one", "camera.example");
        CameraProfile second = profile("two", "http://CAMERA.EXAMPLE:8080/ccapi");

        assertThat(CameraLeaseKey.fromCcapiProfile(first))
                .isEqualTo(CameraLeaseKey.fromCcapiProfile(second));
    }

    @Test
    void canonicalizesBracketedIpv6() {
        CameraLeaseKey expanded = CameraLeaseKey.fromCcapiLocation(
                "http://[2001:0db8:0:0:0:0:0:1]:8080/ccapi");
        CameraLeaseKey compressed = CameraLeaseKey.fromCcapiLocation(
                "http://[2001:db8::1]:8080");

        assertThat(expanded).isEqualTo(compressed);
        assertThat(expanded.canonicalOriginForTesting())
                .isEqualTo("http://[2001:db8:0:0:0:0:0:1]:8080");
    }

    @Test
    void rejectsCredentialBearingAndMalformedLocations() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CameraLeaseKey.fromCcapiLocation(
                        "http://user:secret@camera.example:8080"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CameraLeaseKey.fromCcapiLocation("ftp://camera.example"));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> CameraLeaseKey.fromCcapiLocation("http:///missing-host"));
    }

    @Test
    void invalidOriginNeverSurvivesInExceptionCauseChain() {
        IllegalArgumentException failure = catchThrowableOfType(
                IllegalArgumentException.class,
                () -> CameraLeaseKey.fromCcapiLocation(
                        "http://user:super-secret@[invalid-private-host"));

        assertThat(failure.getCause()).isNull();
        assertThat(failure.getMessage())
                .doesNotContain("super-secret")
                .doesNotContain("invalid-private-host")
                .doesNotContain("user");
    }

    @Test
    void publicStringNeverContainsOriginOrHost() {
        CameraLeaseKey key = CameraLeaseKey.fromCcapiLocation(
                "http://private-camera.example:8080/ccapi");

        assertThat(key.toString())
                .doesNotContain("private-camera")
                .doesNotContain("8080")
                .contains(key.redactedId());
        assertThat(key.redactedId()).matches("[0-9a-f]{16}");
    }

    private static CameraProfile profile(String id, String host) {
        return new CameraProfile(
                id, "Camera " + id, "canon-ccapi", host,
                10, 30, 0.1);
    }
}

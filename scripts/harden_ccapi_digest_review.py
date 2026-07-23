from pathlib import Path


FACTORY = Path(
    "app/src/main/java/com/photographer/focusstack/camera/backend/"
    "CcapiRestTemplateFactory.java"
)
TEST = Path(
    "app/src/test/java/com/photographer/focusstack/camera/backend/"
    "CcapiDigestChallengeSelectionTest.java"
)

OLD_VALIDATOR = '''    private void validateCanonDigestChallenge(String profileId, Header[] challenges) {
        for (Header challenge : challenges) {
            String value = challenge.getValue();
            if (value == null || !value.regionMatches(true, 0, "Digest ", 0, 7)) {
                continue;
            }
            String parameters = value.substring(7);
            String algorithm = parameter(parameters, "algorithm").orElse("MD5");
            String qop = parameter(parameters, "qop").orElse("");
            boolean supportsAuth = Arrays.stream(qop.split(","))
                    .map(String::trim)
                    .anyMatch("auth"::equalsIgnoreCase);
            if (!"MD5".equalsIgnoreCase(algorithm) || !supportsAuth) {
                outcomes.record(CcapiOutcomeRecorder.Outcome.AUTH_FINAL_FAILURE);
                throw new CameraAuthenticationException(profileId, CREDENTIALS_REJECTED);
            }
            return;
        }
        // A Basic-only or otherwise non-Digest challenge is not answered because
        // RequestConfig permits Digest only. The bounded final 401 is translated by
        // CcapiResponseErrorHandler without exposing challenge material.
    }'''

NEW_VALIDATOR = '''    private void validateCanonDigestChallenge(String profileId, Header[] challenges) {
        if (hasCompatibleCanonDigestChallenge(challenges)) {
            return;
        }
        if (containsDigestChallenge(challenges)) {
            outcomes.record(CcapiOutcomeRecorder.Outcome.AUTH_FINAL_FAILURE);
            throw new CameraAuthenticationException(profileId, CREDENTIALS_REJECTED);
        }
        // A Basic-only or otherwise non-Digest challenge is not answered because
        // RequestConfig permits Digest only. The bounded final 401 is translated by
        // CcapiResponseErrorHandler without exposing challenge material.
    }

    static boolean hasCompatibleCanonDigestChallenge(Header[] challenges) {
        for (Header challenge : challenges) {
            String value = challenge.getValue();
            if (!isDigestChallenge(value)) {
                continue;
            }
            String parameters = value.substring(7);
            String algorithm = parameter(parameters, "algorithm").orElse("MD5");
            String qop = parameter(parameters, "qop").orElse("");
            boolean supportsAuth = Arrays.stream(qop.split(","))
                    .map(String::trim)
                    .anyMatch("auth"::equalsIgnoreCase);
            if ("MD5".equalsIgnoreCase(algorithm) && supportsAuth) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsDigestChallenge(Header[] challenges) {
        return Arrays.stream(challenges)
                .map(Header::getValue)
                .anyMatch(CcapiRestTemplateFactory::isDigestChallenge);
    }

    private static boolean isDigestChallenge(String value) {
        return value != null && value.regionMatches(true, 0, "Digest ", 0, 7);
    }'''

TEST_SOURCE = '''package com.photographer.focusstack.camera.backend;

import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BasicHeader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CcapiDigestChallengeSelectionTest {

    @Test
    void acceptsCompatibleDigestChallengeAfterUnsupportedAlternative() {
        Header[] challenges = {
                challenge("Digest realm=\\"camera\\", algorithm=\\"SHA-256\\", qop=\\"auth\\""),
                challenge("Digest realm=\\"camera\\", algorithm=\\"MD5\\", qop=\\"auth,auth-int\\"")
        };

        assertThat(CcapiRestTemplateFactory
                .hasCompatibleCanonDigestChallenge(challenges)).isTrue();
    }

    @Test
    void acceptsOmittedAlgorithmAsMd5Default() {
        Header[] challenges = {
                challenge("Digest realm=\\"camera\\", qop=\\"auth\\"")
        };

        assertThat(CcapiRestTemplateFactory
                .hasCompatibleCanonDigestChallenge(challenges)).isTrue();
    }

    @Test
    void rejectsDigestSetWithoutCompatibleCanonContract() {
        Header[] challenges = {
                challenge("Basic realm=\\"camera\\""),
                challenge("Digest realm=\\"camera\\", algorithm=\\"SHA-256\\", qop=\\"auth\\""),
                challenge("Digest realm=\\"camera\\", algorithm=\\"MD5\\", qop=\\"auth-int\\"")
        };

        assertThat(CcapiRestTemplateFactory
                .hasCompatibleCanonDigestChallenge(challenges)).isFalse();
    }

    private static Header challenge(String value) {
        return new BasicHeader("WWW-Authenticate", value);
    }
}
'''


def main() -> None:
    text = FACTORY.read_text(encoding="utf-8")
    count = text.count(OLD_VALIDATOR)
    if count != 1:
        raise SystemExit(f"expected one Digest validator anchor, found {count}")
    FACTORY.write_text(text.replace(OLD_VALIDATOR, NEW_VALIDATOR), encoding="utf-8")
    TEST.write_text(TEST_SOURCE, encoding="utf-8")


if __name__ == "__main__":
    main()

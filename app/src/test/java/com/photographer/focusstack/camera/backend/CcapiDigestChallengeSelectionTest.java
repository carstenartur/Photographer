package com.photographer.focusstack.camera.backend;

import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BasicHeader;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CcapiDigestChallengeSelectionTest {

    @Test
    void acceptsCompatibleDigestChallengeAfterUnsupportedAlternative() {
        Header[] challenges = {
                challenge("Digest realm=\"camera\", algorithm=\"SHA-256\", qop=\"auth\""),
                challenge("Digest realm=\"camera\", algorithm=\"MD5\", qop=\"auth,auth-int\"")
        };

        assertThat(CcapiRestTemplateFactory
                .hasCompatibleCanonDigestChallenge(challenges)).isTrue();
    }

    @Test
    void acceptsOmittedAlgorithmAsMd5Default() {
        Header[] challenges = {
                challenge("Digest realm=\"camera\", qop=\"auth\"")
        };

        assertThat(CcapiRestTemplateFactory
                .hasCompatibleCanonDigestChallenge(challenges)).isTrue();
    }

    @Test
    void rejectsDigestSetWithoutCompatibleCanonContract() {
        Header[] challenges = {
                challenge("Basic realm=\"camera\""),
                challenge("Digest realm=\"camera\", algorithm=\"SHA-256\", qop=\"auth\""),
                challenge("Digest realm=\"camera\", algorithm=\"MD5\", qop=\"auth-int\"")
        };

        assertThat(CcapiRestTemplateFactory
                .hasCompatibleCanonDigestChallenge(challenges)).isFalse();
    }

    private static Header challenge(String value) {
        return new BasicHeader("WWW-Authenticate", value);
    }
}

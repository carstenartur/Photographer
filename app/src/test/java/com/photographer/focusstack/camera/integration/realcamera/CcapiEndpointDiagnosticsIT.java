package com.photographer.focusstack.camera.integration.realcamera;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import com.photographer.focusstack.camera.backend.CcapiEvidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.Isolated;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Opt-in catalog and ability snapshot. See docs/ccapi-capabilities.md for the dedicated invocation. */
@RealCamera
@Isolated
@EnabledIfEnvironmentVariable(named = "CCAPI_EVIDENCE_URL", matches = ".+")
class CcapiEndpointDiagnosticsIT {
    @Test
    void writeReadOnlyCapabilityEvidence() throws Exception {
        var output = Path.of(System.getenv().getOrDefault("CCAPI_EVIDENCE_OUTPUT", "target/ccapi-capabilities.json"));
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        // The HTTP stack can log hosts, cookies and challenge headers at debug/trace levels.
        var silence = new TurboFilter() {
            @Override
            public FilterReply decide(Marker marker, Logger logger, Level level, String format,
                                      Object[] params, Throwable throwable) {
                return FilterReply.DENY;
            }
        };
        silence.start();
        String evidence;
        context.addTurboFilter(silence);
        try {
            var mode = CcapiEvidence.ConnectionMode.valueOf(System.getenv("CCAPI_EVIDENCE_MODE"));
            evidence = CcapiEvidence.capture(System.getenv("CCAPI_EVIDENCE_URL"), mode,
                    System.getenv("CCAPI_EVIDENCE_USERNAME"), System.getenv("CCAPI_EVIDENCE_PASSWORD"));
        } catch (Exception failure) {
            // Exceptions from HTTP or URI parsing may themselves contain identifying details.
            throw new AssertionError("Capability capture failed (" + failure.getClass().getSimpleName()
                    + "). Check the camera, connection and credentials.");
        } finally {
            context.getTurboFilterList().remove(silence);
            silence.stop();
        }
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(output, evidence, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }
}

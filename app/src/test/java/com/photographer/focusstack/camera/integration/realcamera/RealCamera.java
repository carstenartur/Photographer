package com.photographer.focusstack.camera.integration.realcamera;

import org.junit.jupiter.api.Tag;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Composed JUnit 5 annotation that marks a test as requiring a <em>real</em>
 * Canon camera reachable through the transport configured by the selected test.
 *
 * <p>Tests annotated with {@code @RealCamera} are <strong>excluded from normal
 * CI and default {@code mvn verify} runs</strong> via the {@code real-camera}
 * {@link Tag}. Run the real-camera group explicitly while keeping Docker-only
 * tests excluded:
 *
 * <pre>
 *   mvn -B verify -pl app -am \
 *       -Dgroups=real-camera \
 *       -DexcludedGroups=docker \
 *       -Djava.awt.headless=true
 * </pre>
 *
 * <p>Prefer selecting the intended test class and read its configuration
 * requirements before execution. The camera must be powered on, connected by
 * the required CCAPI/PTP mode and left in a safe state for the operations under
 * test.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Tag("real-camera")
public @interface RealCamera {
}

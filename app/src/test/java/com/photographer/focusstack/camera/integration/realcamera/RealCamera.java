package com.photographer.focusstack.camera.integration.realcamera;

import org.junit.jupiter.api.Tag;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Composed JUnit 5 annotation that marks a test as requiring a <em>real</em>
 * Canon camera reachable on the local network.
 *
 * <p>Tests annotated with {@code @RealCamera} are <strong>excluded from normal
 * CI / {@code mvn verify} runs</strong> via the {@code real-camera}
 * {@link Tag}.  They can be run on-demand with:
 *
 * <pre>
 *   mvn verify -pl app -am -Djava.awt.headless=true -Dgroups=real-camera
 * </pre>
 *
 * <p>The camera must be powered on and connected to the same network as the
 * machine executing the tests.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Tag("real-camera")
public @interface RealCamera {
}

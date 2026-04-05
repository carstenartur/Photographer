package com.photographer.focusstack;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Single entry point for the Focus Stack application.
 *
 * <p>This application merges the former {@code camera-service} and
 * {@code analysis-service} into one Spring Boot process, serving:
 * <ul>
 *   <li>Camera control REST API ({@code /api/cameras})</li>
 *   <li>Stacking job management REST API ({@code /api/jobs})</li>
 *   <li>Image analysis REST API ({@code /api/analysis})</li>
 *   <li>WebSocket live-view / progress events ({@code /ws})</li>
 *   <li>Embedded React SPA ({@code /}) served as static resources</li>
 * </ul>
 *
 * <p>Run with {@code mvn spring-boot:run} in the {@code app} module for local
 * development.  Use the {@code docker} Spring profile for PostgreSQL-backed
 * deployments.
 */
@SpringBootApplication
@EnableAsync
@ConfigurationPropertiesScan("com.photographer.focusstack.app.config")
public class FocusStackApplication {

    public static void main(String[] args) {
        SpringApplication.run(FocusStackApplication.class, args);
    }
}

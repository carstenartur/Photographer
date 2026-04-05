package com.photographer.focusstack.camera.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared base class for camera-service integration tests that need PostgreSQL.
 * Uses a single static Testcontainers PostgreSQL container that is started once
 * and reused across all test classes extending this base, avoiding the overhead
 * of starting a separate container per test class.
 *
 * <p>This uses the <em>singleton container pattern</em>: the container is
 * started in a static initializer block and stays running for the JVM
 * lifetime. Testcontainers' Ryuk reaper handles cleanup after the JVM exits.</p>
 *
 * <h3>Resource Requirements</h3>
 * <ul>
 *   <li>Docker must be running</li>
 *   <li>Minimum 512 MB free RAM for the PostgreSQL container</li>
 *   <li>Testcontainers Ryuk container for automatic cleanup</li>
 * </ul>
 */
abstract class AbstractPostgresIntegrationTest {

    static final PostgreSQLContainer<?> postgres;

    static {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }
}

package com.photographer.focusstack.camera.integration;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Shared base class for camera-service integration tests that need PostgreSQL.
 * Uses a single static Testcontainers PostgreSQL container that is reused
 * across all test classes extending this base, avoiding the overhead of
 * starting a separate container per test class.
 *
 * <h3>Resource Requirements</h3>
 * <ul>
 *   <li>Docker must be running</li>
 *   <li>Minimum 512 MB free RAM for the PostgreSQL container</li>
 *   <li>Testcontainers Ryuk container for automatic cleanup</li>
 * </ul>
 */
@Testcontainers
abstract class AbstractPostgresIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withReuse(true);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }
}

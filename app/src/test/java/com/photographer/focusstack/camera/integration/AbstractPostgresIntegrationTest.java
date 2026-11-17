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
 * <p>The DDL strategy is set to {@code update} rather than {@code create-drop}
 * to prevent {@code @DataJpaTest} slice contexts (e.g. {@link StackingJobRepositoryIT},
 * {@link CameraProfileRepositoryIT}) from dropping the tables that the full
 * {@code @SpringBootTest} contexts (e.g. {@link ValidationIT}, {@link CameraServiceIT})
 * rely on. Both context types share the same singleton Testcontainers container.
 * With {@code create-drop}, the slice contexts DROP all tables—including the
 * {@code users} table—causing authentication failures in subsequently re-used
 * {@code @SpringBootTest} contexts. Schema drift is not a concern here because
 * Testcontainers starts a fresh database container for every Maven build.</p>
 *
 * <h3>Resource Requirements</h3>
 * <ul>
 *   <li>Docker must be running</li>
 *   <li>Minimum 512 MB free RAM for the PostgreSQL container</li>
 *   <li>Testcontainers Ryuk container for automatic cleanup</li>
 * </ul>
 */
public abstract class AbstractPostgresIntegrationTest {

    static final PostgreSQLContainer<?> postgres;

    static {
        postgres = new PostgreSQLContainer<>("postgres:16-alpine");
        postgres.start();
    }

    @DynamicPropertySource
    public static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "update");
        // Use in-memory Lucene indexes to avoid file-system write.lock contention
        // between Spring contexts that share the same JVM in the failsafe fork
        registry.add("spring.jpa.properties.hibernate.search.backend.directory.type", () -> "local-heap");
    }
}

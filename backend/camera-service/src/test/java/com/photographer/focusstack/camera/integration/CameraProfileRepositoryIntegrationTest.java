package com.photographer.focusstack.camera.integration;

import com.photographer.focusstack.camera.entity.CameraProfileEntity;
import com.photographer.focusstack.camera.repository.CameraProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@Testcontainers
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class CameraProfileRepositoryIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }

    @Autowired
    private CameraProfileRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void saveAndFindById() {
        CameraProfileEntity entity = new CameraProfileEntity(
                "repo-cam-1", "Repo Camera", "mock", "localhost", 10, 30, 0.1);
        repository.save(entity);

        Optional<CameraProfileEntity> found = repository.findById("repo-cam-1");
        assertTrue(found.isPresent());
        assertEquals("Repo Camera", found.get().getName());
        assertEquals("mock", found.get().getBackendType());
    }

    @Test
    void findAll() {
        repository.save(new CameraProfileEntity("cam-a", "Camera A", "mock", "host1", 5, 10, 0.1));
        repository.save(new CameraProfileEntity("cam-b", "Camera B", "ccapi", "host2", 10, 20, 0.2));

        List<CameraProfileEntity> all = repository.findAll();
        assertEquals(2, all.size());
    }

    @Test
    void deleteById() {
        repository.save(new CameraProfileEntity("cam-del", "Delete Me", "mock", "host", 5, 10, 0.1));
        assertTrue(repository.existsById("cam-del"));

        repository.deleteById("cam-del");
        assertFalse(repository.existsById("cam-del"));
    }

    @Test
    void updateExisting() {
        CameraProfileEntity entity = new CameraProfileEntity(
                "cam-upd", "Original", "mock", "host", 5, 10, 0.1);
        repository.save(entity);

        entity.setName("Updated");
        entity.setBackendType("ccapi");
        repository.save(entity);

        Optional<CameraProfileEntity> updated = repository.findById("cam-upd");
        assertTrue(updated.isPresent());
        assertEquals("Updated", updated.get().getName());
        assertEquals("ccapi", updated.get().getBackendType());
    }
}

package com.photographer.focusstack.camera.integration;

import com.photographer.focusstack.camera.entity.StackingJobEntity;
import com.photographer.focusstack.camera.repository.StackingJobRepository;
import com.photographer.focusstack.common.model.JobStatus;
import com.photographer.focusstack.common.model.QualityMode;
import com.photographer.focusstack.common.model.StackingJob;
import com.photographer.focusstack.common.model.StackingParameters;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class StackingJobRepositoryIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private StackingJobRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    @Test
    void saveAndRetrieveJob() {
        StackingParameters params = new StackingParameters(0, 5, 10, 0.05, true, QualityMode.BALANCED);
        StackingJob job = new StackingJob("job-1", "cam-1", JobStatus.CREATED, null, null, params, new ArrayList<>(), null);

        repository.save(StackingJobEntity.fromRecord(job));

        Optional<StackingJobEntity> found = repository.findById("job-1");
        assertTrue(found.isPresent());
        StackingJob retrieved = found.get().toRecord();
        assertEquals("job-1", retrieved.id());
        assertEquals(JobStatus.CREATED, retrieved.status());
        assertEquals(5, retrieved.parameters().stepSize());
    }

    @Test
    void findByStatus() {
        StackingParameters params = new StackingParameters(0, 5, 10, 0.05, true, QualityMode.FAST);
        repository.save(StackingJobEntity.fromRecord(
                new StackingJob("j1", "cam-1", JobStatus.CREATED, null, null, params, new ArrayList<>(), null)));
        repository.save(StackingJobEntity.fromRecord(
                new StackingJob("j2", "cam-1", JobStatus.RUNNING, null, null, params, new ArrayList<>(), null)));
        repository.save(StackingJobEntity.fromRecord(
                new StackingJob("j3", "cam-2", JobStatus.CREATED, null, null, params, new ArrayList<>(), null)));

        List<StackingJobEntity> created = repository.findByStatus(JobStatus.CREATED);
        assertEquals(2, created.size());

        List<StackingJobEntity> running = repository.findByStatus(JobStatus.RUNNING);
        assertEquals(1, running.size());
    }

    @Test
    void findByCameraProfileId() {
        StackingParameters params = new StackingParameters(0, 5, 10, 0.05, false, QualityMode.MAXIMUM_QUALITY);
        repository.save(StackingJobEntity.fromRecord(
                new StackingJob("j1", "cam-1", JobStatus.CREATED, null, null, params, new ArrayList<>(), null)));
        repository.save(StackingJobEntity.fromRecord(
                new StackingJob("j2", "cam-1", JobStatus.COMPLETED, null, null, params, new ArrayList<>(), null)));
        repository.save(StackingJobEntity.fromRecord(
                new StackingJob("j3", "cam-2", JobStatus.CREATED, null, null, params, new ArrayList<>(), null)));

        List<StackingJobEntity> cam1Jobs = repository.findByCameraProfileId("cam-1");
        assertEquals(2, cam1Jobs.size());
    }

    @Test
    void jobWithFramesPersistence() {
        StackingParameters params = new StackingParameters(0, 5, 10, 0.05, true, QualityMode.BALANCED);
        List<String> frames = List.of("frame-1", "frame-2", "frame-3");
        StackingJob job = new StackingJob("job-frames", "cam-1", JobStatus.COMPLETED, null, null, params, frames, null);

        repository.save(StackingJobEntity.fromRecord(job));

        StackingJob retrieved = repository.findById("job-frames").get().toRecord();
        assertEquals(3, retrieved.frames().size());
        assertEquals("frame-1", retrieved.frames().get(0));
        assertEquals("frame-3", retrieved.frames().get(2));
    }
}

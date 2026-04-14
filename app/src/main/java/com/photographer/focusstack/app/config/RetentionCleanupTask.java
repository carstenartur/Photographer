package com.photographer.focusstack.app.config;

import com.photographer.focusstack.astro.service.AstroModeService;
import com.photographer.focusstack.camera.entity.StackingJobEntity;
import com.photographer.focusstack.camera.repository.StackingJobRepository;
import com.photographer.focusstack.camera.service.StackingJobService;
import com.photographer.focusstack.common.model.JobStatus;
import com.photographer.focusstack.observer.service.ObserverService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Scheduled task that automatically cleans up old completed/failed/cancelled data
 * based on configurable retention periods.
 *
 * <p>Enabled when {@code focusstack.retention.enabled=true}. Runs according to the
 * cron expression in {@code focusstack.retention.cron} (default: 3 AM daily).
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(RetentionConfig.class)
@ConditionalOnProperty(name = "focusstack.retention.enabled", havingValue = "true")
public class RetentionCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(RetentionCleanupTask.class);
    private static final Set<JobStatus> DELETABLE_JOB_STATES =
            Set.of(JobStatus.COMPLETED, JobStatus.FAILED, JobStatus.CANCELLED);

    private final RetentionConfig config;
    private final StackingJobService jobService;
    private final StackingJobRepository jobRepository;
    private final AstroModeService astroModeService;
    private final ObserverService observerService;

    public RetentionCleanupTask(RetentionConfig config,
                                StackingJobService jobService,
                                StackingJobRepository jobRepository,
                                AstroModeService astroModeService,
                                ObserverService observerService) {
        this.config = config;
        this.jobService = jobService;
        this.jobRepository = jobRepository;
        this.astroModeService = astroModeService;
        this.observerService = observerService;
        log.info("Retention cleanup enabled: jobs={}d, events={}d, astro={}d, cron='{}'",
                config.jobsMaxAgeDays(), config.eventsMaxAgeDays(),
                config.astroMaxAgeDays(), config.cron());
    }

    @Scheduled(cron = "${focusstack.retention.cron:0 0 3 * * *}")
    public void runCleanup() {
        log.info("Starting retention cleanup");
        int totalDeleted = 0;

        // Clean old stacking jobs
        if (config.jobsMaxAgeDays() > 0) {
            Instant cutoff = Instant.now().minus(Duration.ofDays(config.jobsMaxAgeDays()));
            List<StackingJobEntity> oldJobs = jobRepository.findByStatusInAndCompletedAtBefore(
                    DELETABLE_JOB_STATES, cutoff);
            int jobsDeleted = 0;
            for (StackingJobEntity entity : oldJobs) {
                try {
                    jobService.deleteJob(entity.getId());
                    jobsDeleted++;
                } catch (Exception e) {
                    log.warn("Failed to delete job {}: {}", entity.getId(), e.getMessage());
                }
            }
            totalDeleted += jobsDeleted;
            if (jobsDeleted > 0) {
                log.info("Retention: deleted {} stacking jobs older than {} days",
                        jobsDeleted, config.jobsMaxAgeDays());
            }
        }

        // Clean old observation events
        if (config.eventsMaxAgeDays() > 0) {
            Instant cutoff = Instant.now().minus(Duration.ofDays(config.eventsMaxAgeDays()));
            long eventsDeleted = observerService.deleteEventsOlderThan(cutoff);
            totalDeleted += (int) eventsDeleted;
            if (eventsDeleted > 0) {
                log.info("Retention: deleted {} observation events older than {} days",
                        eventsDeleted, config.eventsMaxAgeDays());
            }
        }

        // Clean old astro sessions
        if (config.astroMaxAgeDays() > 0) {
            Instant cutoff = Instant.now().minus(Duration.ofDays(config.astroMaxAgeDays()));
            int astroDeleted = 0;
            for (var session : astroModeService.listSessions()) {
                if (session.finishedAt() != null && session.finishedAt().isBefore(cutoff)) {
                    try {
                        if (astroModeService.deleteSession(session.id())) {
                            astroDeleted++;
                        }
                    } catch (Exception e) {
                        log.warn("Failed to delete astro session {}: {}",
                                session.id(), e.getMessage());
                    }
                }
            }
            totalDeleted += astroDeleted;
            if (astroDeleted > 0) {
                log.info("Retention: deleted {} astro sessions older than {} days",
                        astroDeleted, config.astroMaxAgeDays());
            }
        }

        log.info("Retention cleanup finished — {} items deleted", totalDeleted);
    }
}

package com.photographer.focusstack.camera.search;

import com.photographer.focusstack.camera.entity.CameraProfileEntity;
import com.photographer.focusstack.camera.entity.StackingJobEntity;
import jakarta.persistence.EntityManager;
import org.hibernate.search.mapper.orm.Search;
import org.hibernate.search.mapper.orm.session.SearchSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

@Service
public class SearchService {

    private static final Logger log = LoggerFactory.getLogger(SearchService.class);

    private final EntityManager entityManager;

    public SearchService(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public List<CameraProfileEntity> searchCameraProfiles(String query, int maxResults) {
        try {
            SearchSession searchSession = Search.session(entityManager);
            return searchSession.search(CameraProfileEntity.class)
                    .where(f -> f.bool()
                            .should(f.match().fields("name", "host").matching(query))
                            .should(f.match().field("backendType").matching(query))
                    )
                    .fetchHits(maxResults);
        } catch (Exception e) {
            log.warn("Camera profile search failed for query '{}': {}", query, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Transactional(readOnly = true)
    public List<StackingJobEntity> searchStackingJobs(String query, int maxResults) {
        try {
            SearchSession searchSession = Search.session(entityManager);
            return searchSession.search(StackingJobEntity.class)
                    .where(f -> f.match().field("cameraProfileId").matching(query))
                    .fetchHits(maxResults);
        } catch (Exception e) {
            log.warn("Job search failed for query '{}': {}", query, e.getMessage());
            return Collections.emptyList();
        }
    }

    @Transactional
    public void reindexAll() {
        try {
            SearchSession searchSession = Search.session(entityManager);
            searchSession.massIndexer(CameraProfileEntity.class, StackingJobEntity.class)
                    .startAndWait();
            log.info("Hibernate Search mass indexing completed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Mass indexing interrupted", e);
        }
    }
}

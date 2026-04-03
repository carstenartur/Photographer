package com.photographer.focusstack.camera.search;

import org.hibernate.search.mapper.orm.Search;
import jakarta.persistence.EntityManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class HibernateSearchInitializer {

    private static final Logger log = LoggerFactory.getLogger(HibernateSearchInitializer.class);

    private final EntityManager entityManager;

    public HibernateSearchInitializer(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onApplicationReady() {
        try {
            log.info("Starting Hibernate Search mass indexing on startup...");
            Search.session(entityManager)
                    .massIndexer()
                    .startAndWait();
            log.info("Hibernate Search mass indexing completed");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Hibernate Search mass indexing interrupted", e);
        }
    }
}

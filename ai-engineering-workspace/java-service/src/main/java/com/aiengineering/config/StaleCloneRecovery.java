package com.aiengineering.config;

import com.aiengineering.entity.IndexStatus;
import com.aiengineering.repository.RepoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Clones run on in-memory background threads, so a restart mid-clone loses
 * them. On startup, anything still marked CLONING can never finish - mark it
 * FAILED so the user sees an error and can reconnect, instead of waiting forever.
 * (Assumes a single instance, like LoginCodeService.)
 */
@Component
public class StaleCloneRecovery {

    private static final Logger log = LoggerFactory.getLogger(StaleCloneRecovery.class);

    private final RepoRepository repoRepository;

    public StaleCloneRecovery(RepoRepository repoRepository) {
        this.repoRepository = repoRepository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void failInterruptedClones() {
        var stale = repoRepository.findByStatus(IndexStatus.CLONING);
        stale.forEach(repo -> {
            repo.setStatus(IndexStatus.FAILED);
            repo.setErrorMessage("Clone was interrupted by a server restart - please reconnect the repository");
        });
        repoRepository.saveAll(stale);
        if (!stale.isEmpty()) {
            log.warn("Marked {} interrupted clone(s) as FAILED", stale.size());
        }
    }
}

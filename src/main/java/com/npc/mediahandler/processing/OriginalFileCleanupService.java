package com.npc.mediahandler.processing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Periodically deletes source files for records that were processed in copy mode
 * and have passed their scheduled deletion time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OriginalFileCleanupService {

    private final MediaFileRepository repository;

    @Scheduled(fixedDelayString = "${media.cleanup-interval-ms:1800000}")
    public void deleteExpiredOriginals() {
        List<MediaFileRecord> due = repository.findBySourceDeleteAfterBefore(Instant.now());
        if (due.isEmpty()) return;

        log.info("Cleanup: {} original file(s) scheduled for deletion", due.size());
        for (MediaFileRecord record : due) {
            Path source = Path.of(record.getSourcePath());
            try {
                if (Files.exists(source)) {
                    Files.delete(source);
                    log.info("Deleted original: {}", source);
                } else {
                    log.info("Original already gone, skipping: {}", source);
                }
            } catch (IOException e) {
                log.warn("Failed to delete original '{}': {}", source, e.getMessage());
                continue; // leave sourceDeleteAfter set so it retries next cycle
            }
            record.setSourceDeleteAfter(null);
            repository.save(record);
        }
    }
}

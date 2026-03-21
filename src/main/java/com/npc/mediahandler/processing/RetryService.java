package com.npc.mediahandler.processing;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.npc.mediahandler.config.MediaProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class RetryService {

    private static final List<MediaFileStatus> RETRYABLE =
            List.of(MediaFileStatus.LLM_FAILED, MediaFileStatus.TMDB_FAILED, MediaFileStatus.MOVE_FAILED);

    private final MediaFileRepository repository;
    private final FileProcessingService fileProcessingService;
    private final MediaProperties properties;
    private final ProcessingGateService gate;

    @Scheduled(fixedDelayString = "${media.retry.interval-ms:300000}")
    public void retryFailed() {
        if (!gate.isRunning()) {
            log.debug("Processing stopped — skipping retry scan");
            return;
        }
        if (!properties.getRetry().isEnabled()) {
            return;
        }
        int maxAttempts = properties.getRetry().getMaxAttempts();
        List<MediaFileRecord> candidates = repository.findLatestRetryable(RETRYABLE, maxAttempts);

        if (candidates.isEmpty()) {
            log.debug("Retry scan: nothing to retry");
            return;
        }

        log.info("Retry scan: {} candidate(s) found", candidates.size());

        for (MediaFileRecord record : candidates) {
            Path source = Path.of(record.getSourcePath());
            if (!Files.exists(source)) {
                log.warn("Retry skipped — source file gone: {}", source);
                continue;
            }
            log.info("Retrying {} (attempt {}/{}) for: {}",
                    record.getStatus(), record.getRetryCount() + 1, maxAttempts, record.getOriginalFilename());
            fileProcessingService.execute(record);
        }
    }
}

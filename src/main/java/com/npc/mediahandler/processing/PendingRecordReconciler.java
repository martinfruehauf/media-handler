package com.npc.mediahandler.processing;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import com.npc.mediahandler.monitor.IgnoredFolders;
import com.npc.mediahandler.monitor.SampleFiles;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * At startup nothing can be in progress, so every PENDING record was left behind by an attempt
 * that never finished (crash, restart, an old version's unhandled error). Each one is closed with
 * a reason, or — if its file is still waiting — left PENDING for the folder monitor, which
 * continues that record when it picks the file up again.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PendingRecordReconciler {

    private final MediaFileRepository repository;
    private final IgnoredFolders ignoredFolders;
    private final SampleFiles sampleFiles;

    @EventListener(ApplicationReadyEvent.class)
    public void closeOrphanedRecords() {
        List<MediaFileRecord> pending = repository.findByStatus(MediaFileStatus.PENDING);
        int closed = 0;
        for (MediaFileRecord record : pending) {
            String reason = closeReason(record);
            if (reason == null) continue;
            record.setStatus(MediaFileStatus.SKIPPED);
            record.setErrorMessage(reason);
            repository.save(record);
            closed++;
        }
        if (closed > 0) {
            log.info("Closed {} of {} unfinished PENDING record(s) left from before the restart", closed, pending.size());
        }
    }

    /** Why the record can be closed, or null if its file should still be processed. */
    private String closeReason(MediaFileRecord record) {
        Long latestId = repository.findTopBySourcePathOrderByIdDesc(record.getSourcePath())
                .map(MediaFileRecord::getId).orElse(record.getId());
        if (!latestId.equals(record.getId())) return "Unfinished; superseded by record #" + latestId;
        Path source;
        try {
            source = Path.of(record.getSourcePath());
        } catch (InvalidPathException e) {
            return null;  // legacy-encoded path — the pipeline's path recovery handles it on retry
        }
        if (!Files.exists(source)) return "Unfinished; source file no longer exists";
        if (ignoredFolders.isIgnored(source)) return "In ignored folder";
        if (sampleFiles.isSample(source)) {
            return "Sample file (name contains 'sample', ≤ %d MB)".formatted(sampleFiles.maxMb());
        }
        return null;
    }
}

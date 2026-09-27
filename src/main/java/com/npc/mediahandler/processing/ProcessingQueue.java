package com.npc.mediahandler.processing;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;

/**
 * The single place where files are processed. Every source — the folder monitor, the retry
 * scheduler and the manual UI actions — submits work here, and one worker thread runs it in order.
 * This keeps the scheduler threads free (a file can take minutes) and guarantees a file is never
 * processed twice at the same time: a path that is already queued or running is not queued again.
 */
@Slf4j
@Component
public class ProcessingQueue {

    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "processing");
        t.setDaemon(true);
        return t;
    });

    /** Source paths that are queued or being processed. */
    private final Set<String> active = ConcurrentHashMap.newKeySet();

    /** Queues {@code task} for {@code sourcePath}. Returns false if that path is already queued or running. */
    public boolean submit(String sourcePath, Runnable task) {
        if (!active.add(sourcePath)) {
            log.debug("Already queued or processing, not queuing again: {}", sourcePath);
            return false;
        }
        worker.execute(() -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("Processing failed for {}: {}", sourcePath, e.getMessage(), e);
            } finally {
                active.remove(sourcePath);
            }
        });
        return true;
    }

    public boolean isActive(String sourcePath) {
        return active.contains(sourcePath);
    }

    /** Snapshot of the source paths that are queued or being processed. */
    public Set<String> activePaths() {
        return Set.copyOf(active);
    }

    @PreDestroy
    void shutdown() {
        worker.shutdownNow();
    }
}

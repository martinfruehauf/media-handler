package com.npc.mediahandler.monitor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import com.npc.mediahandler.config.AppConfigService;
import com.npc.mediahandler.config.MediaProperties;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class FileMonitorService {

    private final MediaProperties properties;
    private final AppConfigService configService;
    private final ApplicationEventPublisher eventPublisher;

    /** Last observed size per file. Updated whenever the size changes. */
    private final Map<Path, Long> lastSeenSizes = new HashMap<>();

    /** Timestamp of when each file's size was last observed to change. */
    private final Map<Path, Instant> sizeStableSince = new HashMap<>();

    /** Files already published as ready — prevents duplicate events. */
    private final Set<Path> publishedFiles = new HashSet<>();

    public FileMonitorService(MediaProperties properties, AppConfigService configService,
            ApplicationEventPublisher eventPublisher) {
        this.properties = properties;
        this.configService = configService;
        this.eventPublisher = eventPublisher;
    }

    @Scheduled(fixedDelayString = "${media.poll-interval-ms:30000}")
    public void scan() {
        String sourceFolderPath = configService.getOrDefault(AppConfigService.SOURCE_FOLDER,
                properties.getSourceFolder());
        if (sourceFolderPath == null || sourceFolderPath.isBlank()) {
            log.warn("media.source-folder is not configured — skipping scan");
            return;
        }

        Path sourceFolder = Paths.get(sourceFolderPath);
        if (!Files.isDirectory(sourceFolder)) {
            log.warn("Source folder does not exist or is not a directory: {}", sourceFolder);
            return;
        }

        log.debug("Scanning source folder: {}", sourceFolder);

        Set<Path> foundFiles = new HashSet<>();

        try (Stream<Path> walk = Files.walk(sourceFolder)) {
            walk.filter(Files::isRegularFile)
                .filter(this::isMediaFile)
                .forEach(file -> {
                    foundFiles.add(file);
                    checkFile(file);
                });
        } catch (IOException e) {
            log.error("Failed to scan source folder: {}", sourceFolder, e);
        }

        // Clean up tracking state for files that have disappeared
        Set<Path> gone = new HashSet<>(lastSeenSizes.keySet());
        gone.removeAll(foundFiles);
        gone.forEach(file -> {
            log.debug("File no longer present, removing from tracking: {}", file);
            lastSeenSizes.remove(file);
            sizeStableSince.remove(file);
            publishedFiles.remove(file);
        });
    }

    private void checkFile(Path file) {
        if (publishedFiles.contains(file)) {
            return;
        }

        long currentSize;
        try {
            currentSize = Files.size(file);
        } catch (IOException e) {
            log.warn("Could not read size of file: {}", file, e);
            return;
        }

        Long previousSize = lastSeenSizes.get(file);

        if (previousSize == null || previousSize != currentSize) {
            // Size changed (or first time seeing the file) — reset stability clock
            if (previousSize == null) {
                log.info("New media file detected: {} ({} bytes)", file.getFileName(), currentSize);
            } else {
                log.debug("File still changing: {} ({} → {} bytes)", file.getFileName(), previousSize, currentSize);
            }
            lastSeenSizes.put(file, currentSize);
            sizeStableSince.put(file, Instant.now());
            return;
        }

        // Size unchanged — check how long it has been stable
        Instant stableSince = sizeStableSince.get(file);
        long stableSeconds = Instant.now().getEpochSecond() - stableSince.getEpochSecond();
        long threshold = properties.getStabilityThresholdSeconds();

        if (stableSeconds >= threshold) {
            log.info("File is stable for {}s (threshold {}s), publishing ready event: {}",
                    stableSeconds, threshold, file.getFileName());
            publishedFiles.add(file);
            eventPublisher.publishEvent(new FileReadyEvent(this, file));
        } else {
            log.debug("File size stable for {}s / {}s: {}", stableSeconds, threshold, file.getFileName());
        }
    }

    private boolean isMediaFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return properties.getFileExtensions().stream()
                .anyMatch(ext -> name.endsWith("." + ext));
    }
}

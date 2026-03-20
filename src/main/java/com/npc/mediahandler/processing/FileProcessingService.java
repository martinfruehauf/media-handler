package com.npc.mediahandler.processing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Optional;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.npc.mediahandler.config.AppConfigService;
import com.npc.mediahandler.config.MediaProperties;
import com.npc.mediahandler.llm.FilenameParserService;
import com.npc.mediahandler.media.MediaMetadata;
import com.npc.mediahandler.monitor.FileReadyEvent;
import com.npc.mediahandler.tmdb.TmdbResult;
import com.npc.mediahandler.tmdb.TmdbService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileProcessingService {

    private final FilenameParserService filenameParserService;
    private final TmdbService tmdbService;
    private final FileRenameService fileRenameService;
    private final MediaFileRepository repository;
    private final AppConfigService configService;
    private final MediaProperties properties;

    @EventListener
    public void onFileReady(FileReadyEvent event) {
        Path source = event.getFile();
        MediaFileRecord record = repository.save(MediaFileRecord.builder()
                .originalFilename(source.getFileName().toString())
                .sourcePath(source.toString())
                .status(MediaFileStatus.PENDING)
                .createdAt(Instant.now())
                .retryCount(0)
                .build());
        execute(record);
    }

    /**
     * Runs the full pipeline for a record. Safe to call from the retry service.
     * Increments retryCount and persists status after every step.
     */
    public void execute(MediaFileRecord record) {
        record.setRetryCount(record.getRetryCount() + 1);
        record.setLastAttemptAt(Instant.now());
        repository.save(record);

        Path source = Path.of(record.getSourcePath());
        if (!Files.exists(source)) {
            log.warn("Source file no longer exists, skipping: {}", source);
            return;
        }

        // Step 1 — LLM filename parse (with folder-name fallback)
        String sourceRoot = configService.getOrDefault(
            AppConfigService.SOURCE_FOLDER, properties.getSourceFolder());
        Path parent = source.getParent();
        String folderHint = (parent != null && !parent.equals(Paths.get(sourceRoot)))
            ? parent.getFileName().toString()
            : null;

        MediaMetadata metadata = filenameParserService.parseWithFolderFallback(
            record.getOriginalFilename(), folderHint);
        if (metadata.isError()) {
            log.warn("LLM parse failed for '{}': {}", record.getOriginalFilename(), metadata.error());
            record.setStatus(MediaFileStatus.LLM_FAILED);
            record.setErrorMessage("LLM: " + metadata.error());
            repository.save(record);
            return;
        }

        // Step 2 — TMDB canonical lookup
        TmdbResult tmdbResult = metadata.isMovie()
                ? tmdbService.searchMovie(metadata.name(), metadata.year())
                : tmdbService.searchShow(metadata.name(), metadata.year());
        if (tmdbResult == null) {
            String msg = "TMDB: no results for '%s' (%s)".formatted(metadata.name(), metadata.year());
            log.warn(msg);
            record.setStatus(MediaFileStatus.TMDB_FAILED);
            record.setErrorMessage(msg);
            repository.save(record);
            return;
        }

        // Step 3 — Rename and move
        try {
            Optional<Path> target = fileRenameService.process(source, metadata, tmdbResult);
            if (target.isEmpty()) {
                record.setStatus(MediaFileStatus.SKIPPED);
                record.setErrorMessage("File already exists at target and overwrite is disabled");
                repository.save(record);
                return;
            }
            record.setStatus(MediaFileStatus.MOVED);
            record.setTargetPath(target.get().toString());
            record.setErrorMessage(null);
            record.setProcessedAt(Instant.now());
            repository.save(record);
            log.info("Successfully processed '{}' → {}", record.getOriginalFilename(), target.get());
        } catch (IOException e) {
            log.error("Move failed for '{}': {}", record.getOriginalFilename(), e.getMessage());
            record.setStatus(MediaFileStatus.MOVE_FAILED);
            record.setErrorMessage("Move failed: " + e.getMessage());
            repository.save(record);
        }
    }
}

package com.npc.mediahandler.processing;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npc.mediahandler.config.AppConfigService;


import com.npc.mediahandler.config.MediaProperties;
import com.npc.mediahandler.llm.FilenameParserService;
import com.npc.mediahandler.media.MediaMetadata;
import com.npc.mediahandler.monitor.FileReadyEvent;
import com.npc.mediahandler.tmdb.TmdbResult;
import com.npc.mediahandler.tmdb.TmdbService;
import com.npc.mediahandler.wiki.WikipediaTitleService;

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
    private final WikipediaTitleService wikiService;

    private static final ObjectMapper MAPPER = new ObjectMapper();

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

        List<ProcessingNote> notes = new ArrayList<>();

        // Step 1 — LLM filename parse (with folder-name fallback)
        String sourceRoot = configService.getOrDefault(
            AppConfigService.SOURCE_FOLDER, properties.getSourceFolder());
        Path parent = source.getParent();
        String folderHint = (parent != null && !parent.equals(Paths.get(sourceRoot)))
            ? parent.getFileName().toString()
            : null;

        if (folderHint != null) {
            notes.add(new ProcessingNote("LLM_FOLDER", "folder hint used: \"" + folderHint + "\""));
        }

        MediaMetadata metadata = filenameParserService.parseWithFolderFallback(
            record.getOriginalFilename(), folderHint);

        if (metadata.isError()) {
            log.warn("LLM parse failed for '{}': {}", record.getOriginalFilename(), metadata.error());
            notes.add(new ProcessingNote("LLM", "parse failed: " + metadata.error()));
            record.setStatus(MediaFileStatus.LLM_FAILED);
            record.setErrorMessage("LLM: " + metadata.error());
            record.setProcessingNotes(toJson(notes));
            repository.save(record);
            return;
        }

        String seInfo = metadata.isMovie() ? "" : ", S%sE%s".formatted(
                nvl(metadata.season()), nvl(metadata.episode()));
        notes.add(new ProcessingNote("LLM",
                "filename=\"%s\" → name=%s, type=%s%s".formatted(
                        record.getOriginalFilename(), metadata.name(), metadata.type(), seInfo)));

        // Step 2a — TMDB (first attempt, original name)
        TmdbResult tmdbResult = searchTmdb(metadata);

        if (tmdbResult != null) {
            notes.add(new ProcessingNote("TMDB_1", "found: id=%s, name=\"%s\"".formatted(
                    tmdbResult.tmdbId(), tmdbResult.name())));
        } else {
            notes.add(new ProcessingNote("TMDB_1", "not found for \"" + metadata.name() + "\""));

            // Step 2b — Wikipedia title translation (if enabled)
            Optional<String> enTitle = wikiService.findEnglishTitle(metadata.name());

            if (enTitle.isPresent()) {
                notes.add(new ProcessingNote("WIKI",
                        "de lookup → en=\"" + enTitle.get() + "\""));

                MediaMetadata metadataEn = new MediaMetadata(
                        metadata.type(), enTitle.get(), metadata.year(),
                        metadata.season(), metadata.episode(), metadata.error());
                tmdbResult = searchTmdb(metadataEn);

                if (tmdbResult != null) {
                    notes.add(new ProcessingNote("TMDB_2",
                            "found after wiki: id=%s, name=\"%s\"".formatted(
                                    tmdbResult.tmdbId(), tmdbResult.name())));
                } else {
                    notes.add(new ProcessingNote("TMDB_2",
                            "still not found for \"" + enTitle.get() + "\""));
                }
            } else {
                notes.add(new ProcessingNote("WIKI",
                        wikiService.isEnabled() ? "no result" : "lookup disabled"));
            }

            if (tmdbResult == null) {
                String msg = "TMDB: no results for '%s' (%s)".formatted(metadata.name(), metadata.year());
                log.warn(msg);
                record.setStatus(MediaFileStatus.TMDB_FAILED);
                record.setErrorMessage(msg);
                record.setProcessingNotes(toJson(notes));
                repository.save(record);
                return;
            }
        }

        // Step 3 — Copy or move
        boolean copyMode = Boolean.parseBoolean(
                configService.getOrDefault(AppConfigService.FILE_COPY_MODE, "false"));
        try {
            Optional<Path> target = fileRenameService.process(source, metadata, tmdbResult);
            if (target.isEmpty()) {
                notes.add(new ProcessingNote("SKIPPED", "file already exists at target and overwrite is disabled"));
                record.setStatus(MediaFileStatus.SKIPPED);
                record.setErrorMessage("File already exists at target and overwrite is disabled");
                record.setProcessingNotes(toJson(notes));
                repository.save(record);
                return;
            }
            String action = copyMode ? "COPIED" : "MOVED";
            notes.add(new ProcessingNote(action, target.get().toString()));
            record.setStatus(MediaFileStatus.MOVED);
            record.setTargetPath(target.get().toString());
            record.setErrorMessage(null);
            record.setProcessedAt(Instant.now());

            if (copyMode) {
                long deleteAfterHours = parseDeleteAfterHours();
                if (deleteAfterHours > 0) {
                    Instant deleteAt = Instant.now().plusSeconds(deleteAfterHours * 3600);
                    record.setSourceDeleteAfter(deleteAt);
                    notes.add(new ProcessingNote("DELETE_SCHEDULED",
                            "original will be deleted after %d h (at %s)".formatted(
                                    deleteAfterHours, deleteAt)));
                } else {
                    notes.add(new ProcessingNote("COPY_KEPT", "original kept (no delete schedule)"));
                }
            } else {
                // Move mode: clean up the source folder after the file is gone
                cleanupSourceFolder(source, notes);
            }

            record.setProcessingNotes(toJson(notes));
            repository.save(record);
            log.info("Successfully processed '{}' → {}", record.getOriginalFilename(), target.get());
        } catch (IOException e) {
            log.error("Move failed for '{}': {}", record.getOriginalFilename(), e.getMessage());
            notes.add(new ProcessingNote("MOVE_FAILED", e.getMessage()));
            record.setStatus(MediaFileStatus.MOVE_FAILED);
            record.setErrorMessage("Move failed: " + e.getMessage());
            record.setProcessingNotes(toJson(notes));
            repository.save(record);
        }
    }

    private TmdbResult searchTmdb(MediaMetadata metadata) {
        return metadata.isMovie()
                ? tmdbService.searchMovie(metadata.name(), metadata.year())
                : tmdbService.searchShow(metadata.name(), metadata.year());
    }

    private String toJson(List<ProcessingNote> notes) {
        try {
            return MAPPER.writeValueAsString(notes);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize processing notes: {}", e.getMessage());
            return null;
        }
    }

    private long parseDeleteAfterHours() {
        String raw = configService.getOrDefault(AppConfigService.FILE_DELETE_ORIGINAL_AFTER_HOURS, "0").trim();
        try {
            return Math.max(0, Long.parseLong(raw));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String nvl(String s) {
        return s != null ? s : "?";
    }

    // ── Source-folder cleanup ─────────────────────────────────────────────────

    private static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mkv", "mp4", "avi", "mov", "wmv", "m4v", "ts", "mpg", "mpeg",
            "divx", "xvid", "flv", "webm", "rmvb", "rm", "vob", "m2ts", "mts"
    );
    /** Anything below this is treated as a sample / junk clip (50 MB). */
    private static final long SMALL_VIDEO_THRESHOLD_BYTES = 50L * 1024 * 1024;

    private void cleanupSourceFolder(Path movedFile, List<ProcessingNote> notes) {
        boolean enabled = Boolean.parseBoolean(
                configService.getOrDefault(AppConfigService.FOLDER_CLEANUP_ENABLED, "true"));
        if (!enabled) return;

        Path folder = movedFile.getParent();
        String sourceRoot = configService.getOrDefault(
                AppConfigService.SOURCE_FOLDER, properties.getSourceFolder());

        // Never attempt to clean up the source root itself
        if (folder == null || folder.equals(Paths.get(sourceRoot))) return;

        // Delete leftover meta / small-video files
        try (Stream<Path> stream = Files.list(folder)) {
            for (Path file : stream.toList()) {
                if (Files.isDirectory(file)) continue;
                String ext = extension(file);
                if (!VIDEO_EXTENSIONS.contains(ext)) {
                    // Non-video meta file — delete silently
                    Files.deleteIfExists(file);
                    log.info("Deleted meta file during folder cleanup: {}", file);
                } else {
                    long size = Files.size(file);
                    if (size < SMALL_VIDEO_THRESHOLD_BYTES) {
                        Files.deleteIfExists(file);
                        String sizeStr = formatSize(size);
                        log.info("Deleted small video file during folder cleanup: {} ({})", file, sizeStr);
                        notes.add(new ProcessingNote("FOLDER_CLEANUP",
                                "deleted small video file \"%s\" (%s)".formatted(
                                        file.getFileName(), sizeStr)));
                    }
                }
            }
        } catch (IOException e) {
            log.warn("Could not list folder for cleanup '{}': {}", folder, e.getMessage());
            return;
        }

        // Attempt to remove the (now hopefully empty) folder
        try {
            Files.delete(folder);
            log.info("Deleted source folder: {}", folder);
            notes.add(new ProcessingNote("FOLDER_DELETED", folder.toString()));
        } catch (IOException e) {
            // Folder not empty or permission issue — not a fatal error
            log.debug("Could not delete source folder '{}' (may not be empty): {}", folder, e.getMessage());
        }
    }

    private static String extension(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return "%.1f KB".formatted(bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return "%.1f MB".formatted(bytes / (1024.0 * 1024));
        return "%.2f GB".formatted(bytes / (1024.0 * 1024 * 1024));
    }
}

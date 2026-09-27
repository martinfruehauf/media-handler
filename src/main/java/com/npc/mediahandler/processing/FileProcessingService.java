package com.npc.mediahandler.processing;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.npc.mediahandler.config.AppConfigService;
import com.npc.mediahandler.monitor.FileReadyEvent;
import com.npc.mediahandler.monitor.IgnoredFolders;
import com.npc.mediahandler.monitor.SampleFiles;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileProcessingService {

    private final TitleResolver titleResolver;
    private final ProcessingQueue queue;
    private final FileRenameService fileRenameService;
    private final MediaFileRepository repository;
    private final AppConfigService configService;
    private final IgnoredFolders ignoredFolders;
    private final SampleFiles sampleFiles;
    private final SourceFolderCleanup sourceFolderCleanup;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Called by the folder monitor; the file is processed on the {@link ProcessingQueue} worker. */
    @EventListener
    public void onFileReady(FileReadyEvent event) {
        Path source = event.getFile();
        queue.submit(source.toString(), () -> processReadyFile(source));
    }

    /**
     * Queues an existing record for (re)processing. Returns false if its file is already queued or
     * being processed — the caller should then leave the record as it is.
     */
    public boolean submit(MediaFileRecord record) {
        Long id = record.getId();
        return queue.submit(record.getSourcePath(), () -> repository.findById(id).ifPresent(this::execute));
    }

    public boolean isQueued(String sourcePath) {
        return queue.isActive(sourcePath);
    }

    private void processReadyFile(Path source) {
        String rawFilename = source.getFileName().toString();
        String originalFilename = rawFilename.contains("\uFFFD")
                ? decodeFilenameOrElse(source, rawFilename)
                : rawFilename;
        // The monitor re-publishes every file after a restart. Continue the file's open record
        // instead of adding a duplicate; SKIPPED stays skipped until the user re-includes it.
        Optional<MediaFileRecord> latest = repository.findTopBySourcePathOrderByIdDesc(source.toString());
        if (latest.isPresent() && latest.get().getStatus() == MediaFileStatus.SKIPPED) {
            log.info("File was skipped before ({}), not processing again: {}",
                    latest.get().getErrorMessage(), source);
            return;
        }
        if (latest.isPresent() && latest.get().getStatus() == MediaFileStatus.MOVED
                && isCopiedOriginal(source, latest.get())) {
            log.debug("Original of an already copied file, not processing again: {}", source);
            return;
        }
        MediaFileRecord record = latest.filter(r -> r.getStatus() != MediaFileStatus.MOVED)
                .orElseGet(() -> repository.save(MediaFileRecord.builder()
                        .originalFilename(originalFilename)
                        .sourcePath(source.toString())
                        .status(MediaFileStatus.PENDING)
                        .createdAt(Instant.now())
                        .retryCount(0)
                        .build()));
        execute(record);
    }

    /**
     * A MOVED record whose source still exists was processed in copy mode — unless the file was
     * replaced afterwards (e.g. downloaded again), which gives it a newer modification time.
     */
    private static boolean isCopiedOriginal(Path source, MediaFileRecord moved) {
        try {
            return moved.getProcessedAt() != null
                    && !Files.getLastModifiedTime(source).toInstant().isAfter(moved.getProcessedAt());
        } catch (IOException e) {
            return true;
        }
    }

    /**
     * Runs the full pipeline for a record on the calling thread. Only the {@link ProcessingQueue}
     * worker calls this; everything else goes through {@link #submit}. Increments retryCount and
     * always leaves the record in a final status.
     */
    void execute(MediaFileRecord record) {
        record.setRetryCount(record.getRetryCount() + 1);
        record.setLastAttemptAt(Instant.now());
        record.setStatus(MediaFileStatus.PENDING);
        repository.save(record);

        List<ProcessingNote> notes = new ArrayList<>();
        // Status to report if something unexpected throws, updated as the pipeline advances
        MediaFileStatus failStatus = MediaFileStatus.LLM_FAILED;
        try {
            Path source;
            try {
                source = Path.of(record.getSourcePath());
            } catch (java.nio.file.InvalidPathException e) {
                source = recoverLegacyPath(record, notes);
                if (source == null) return;
            }
            if (ignoredFolders.isIgnored(source)) {
                // e.g. an old record retried after its folder was added to the ignore list
                log.info("Source is in an ignored folder, leaving it untouched: {}", source);
                finish(record, notes, MediaFileStatus.SKIPPED, "In ignored folder");
                return;
            }
            if (!Files.exists(source)) {
                log.warn("Source file no longer exists, skipping: {}", source);
                finish(record, notes, MediaFileStatus.SKIPPED, "Source file no longer exists");
                return;
            }
            if (sampleFiles.isSample(source)) {
                log.info("Source is a sample file, leaving it untouched: {}", source);
                finish(record, notes, MediaFileStatus.SKIPPED,
                        "Sample file (name contains 'sample', ≤ %d MB)".formatted(sampleFiles.maxMb()));
                return;
            }

            // Steps 1 + 2 — find the title (LLM, TMDB, Wikipedia, name variants, folder names)
            TitleResolver.Resolution resolution = titleResolver.resolve(record.getOriginalFilename(), source, notes);
            if (!resolution.isFound()) {
                log.warn("No title found for '{}': {}", record.getOriginalFilename(), resolution.error());
                finish(record, notes, resolution.failStatus(), resolution.error());
                return;
            }

            // Step 3 — Copy or move
            failStatus = MediaFileStatus.MOVE_FAILED;
            move(record, source, resolution, notes);
        } catch (RuntimeException e) {
            log.error("Unexpected error processing '{}': {}", record.getOriginalFilename(), e.getMessage(), e);
            notes.add(ProcessingNote.fail("ERROR", "unexpected error: " + e));
            finish(record, notes, failStatus, "Unexpected error: " + e.getMessage());
        }
    }

    private void move(MediaFileRecord record, Path source, TitleResolver.Resolution resolution,
            List<ProcessingNote> notes) {
        boolean copyMode = Boolean.parseBoolean(
                configService.getOrDefault(AppConfigService.FILE_COPY_MODE, "false"));
        try {
            Optional<Path> target = fileRenameService.process(source, resolution.metadata(), resolution.tmdb());
            if (target.isEmpty()) {
                notes.add(ProcessingNote.fail("SKIPPED", "file already exists at target and overwrite is disabled"));
                finish(record, notes, MediaFileStatus.SKIPPED, "File already exists at target and overwrite is disabled");
                return;
            }
            notes.add(ProcessingNote.ok(copyMode ? "COPIED" : "MOVED", target.get().toString()));
            record.setTargetPath(target.get().toString());
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
                sourceFolderCleanup.cleanup(source, notes);
            }
            finish(record, notes, MediaFileStatus.MOVED, null);
            log.info("Successfully processed '{}' → {}", record.getOriginalFilename(), target.get());
        } catch (IOException e) {
            log.error("Move failed for '{}': {}", record.getOriginalFilename(), e.getMessage());
            notes.add(ProcessingNote.fail("MOVE_FAILED", e.getMessage()));
            finish(record, notes, MediaFileStatus.MOVE_FAILED, "Move failed: " + e.getMessage());
        }
    }

    private void finish(MediaFileRecord record, List<ProcessingNote> notes, MediaFileStatus status, String error) {
        record.setStatus(status);
        record.setErrorMessage(error);
        record.setProcessingNotes(toJson(notes));
        repository.save(record);
    }

    // ── Legacy-encoded filename recovery ─────────────────────────────────────

    /** Charsets to try (in order) when the JVM's UTF-8 decoding produced U+FFFD replacement chars. */
    private static final List<Charset> LEGACY_CHARSETS = List.of(
            StandardCharsets.ISO_8859_1,
            Charset.forName("windows-1252"),
            Charset.forName("cp850")
    );

    /**
     * Called when {@code Path.of(storedPath)} throws {@link java.nio.file.InvalidPathException}.
     * Finds the file by listing its parent directory and matching the stored filename string
     * (both sides will have the same U+FFFD replacement chars), then attempts to decode the
     * raw filename bytes using common legacy charsets so the LLM receives a clean title.
     */
    private Path recoverLegacyPath(MediaFileRecord record, List<ProcessingNote> notes) {
        String storedPath = record.getSourcePath();
        int lastSlash = storedPath.lastIndexOf('/');
        if (lastSlash < 0) {
            markPathError(record, notes, "Cannot recover: no parent directory in path");
            return null;
        }
        String parentStr   = storedPath.substring(0, lastSlash);
        String storedName  = storedPath.substring(lastSlash + 1);

        Path parentDir;
        try {
            parentDir = Path.of(parentStr);
        } catch (java.nio.file.InvalidPathException ex) {
            markPathError(record, notes, "Parent directory path unresolvable: " + ex.getReason());
            return null;
        }

        try (Stream<Path> stream = Files.list(parentDir)) {
            Optional<Path> match = stream
                    .filter(p -> !Files.isDirectory(p))
                    .filter(p -> p.getFileName().toString().equals(storedName))
                    .findFirst();

            if (match.isEmpty()) {
                markPathError(record, notes, "File not found in parent directory (moved or renamed)");
                return null;
            }

            Path recovered = match.get();
            String decoded = decodeFilename(recovered);
            if (decoded != null && !decoded.equals(record.getOriginalFilename())) {
                notes.add(new ProcessingNote("PATH_RECOVERY",
                        "decoded filename: \"" + decoded + "\" (stored as \"" + record.getOriginalFilename() + "\")"));
                record.setOriginalFilename(decoded);
            } else {
                notes.add(new ProcessingNote("PATH_RECOVERY", "recovered file via directory listing"));
            }
            return recovered;
        } catch (IOException e) {
            markPathError(record, notes, "Cannot list parent directory: " + e.getMessage());
            return null;
        }
    }

    private void markPathError(MediaFileRecord record, List<ProcessingNote> notes, String msg) {
        log.warn("Path recovery failed for '{}': {}", record.getSourcePath(), msg);
        record.setStatus(MediaFileStatus.LLM_FAILED);
        record.setErrorMessage("Path recovery failed: " + msg);
        record.setProcessingNotes(toJson(notes));
        repository.save(record);
    }

    /**
     * Returns a better-decoded filename by extracting raw bytes from the file URI and
     * trying legacy charsets. Returns {@code null} if no improvement over the original.
     */
    private String decodeFilename(Path file) {
        try {
            String rawPath = file.toUri().getRawPath();
            int slash = rawPath.lastIndexOf('/');
            String encoded = slash >= 0 ? rawPath.substring(slash + 1) : rawPath;
            byte[] bytes = percentDecode(encoded);
            for (Charset charset : LEGACY_CHARSETS) {
                String candidate = new String(bytes, charset);
                if (!candidate.contains("\uFFFD")) {
                    log.debug("Decoded filename '{}' using {} → '{}'", file.getFileName(), charset.name(), candidate);
                    return candidate;
                }
            }
        } catch (Exception e) {
            log.debug("Charset detection failed for {}: {}", file, e.getMessage());
        }
        return null;
    }

    private String decodeFilenameOrElse(Path file, String fallback) {
        String decoded = decodeFilename(file);
        return decoded != null ? decoded : fallback;
    }

    private static byte[] percentDecode(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' && i + 2 < s.length()) {
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi >= 0 && lo >= 0) {
                    out.write((hi << 4) | lo);
                    i += 3;
                    continue;
                }
            }
            out.write(c & 0xFF);
            i++;
        }
        return out.toByteArray();
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

}

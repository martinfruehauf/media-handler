package com.npc.mediahandler.processing;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.npc.mediahandler.config.AppConfigService;
import com.npc.mediahandler.config.MediaProperties;
import com.npc.mediahandler.monitor.IgnoredFolders;
import com.npc.mediahandler.monitor.SampleFiles;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Removes release leftovers from the source folder, in two situations:
 * <ul>
 *   <li>right after a move: the folder the file came from, then empty parents up to the source root</li>
 *   <li>periodically ({@link #sweepStaleFolders}): each top-level folder of the source root that
 *       MediaHandler has a record for, that hasn't changed for {@code folder.cleanup.stale-hours}
 *       and that contains nothing left to process</li>
 * </ul>
 * Within a folder and all its subfolders:
 * <ul>
 *   <li>non-video files (.nfo, .jpg, .srt, ...) are deleted</li>
 *   <li>video files below {@code folder.cleanup.small-video-max-mb} and sample files are deleted —
 *       except small files with an episode marker (S01E03, 1x03), which are unprocessed episodes
 *       of a season pack rather than extras</li>
 *   <li>larger video files are kept (still to be processed)</li>
 *   <li>every folder left empty is removed</li>
 * </ul>
 * Never deleted: anything while an archive or partial download is present, a video file that is
 * queued or has an open record (pending or failed — e.g. a foreign film TMDB couldn't match), and
 * anything in an ignored folder.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SourceFolderCleanup {

    public static final int DEFAULT_SMALL_VIDEO_MAX_MB = 200;
    public static final int DEFAULT_STALE_HOURS = 6;

    /** Archive/compressed formats that indicate an active or pending download — never delete these or their folder. */
    private static final Set<String> ARCHIVE_EXTENSIONS = Set.of(
            "zip", "rar", "7z", "gz", "bz2", "xz", "tar",
            "r00", "r01", "r02", "r03", "r04", "r05",
            "001", "002", "003",
            "nzb", "par", "par2",
            // partial downloads
            "part", "partial", "crdownload", "!qb", "!ut", "tmp"
    );

    /** Record statuses that mean the file still needs attention — such a file is never deleted. */
    private static final Set<MediaFileStatus> OPEN_STATUSES = Set.of(
            MediaFileStatus.PENDING, MediaFileStatus.LLM_FAILED,
            MediaFileStatus.TMDB_FAILED, MediaFileStatus.MOVE_FAILED);

    private static final Pattern EPISODE_MARKER = Pattern.compile(
            "(?i)(?<![a-z0-9])s\\d{1,2}[ ._-]*e\\d{1,3}|(?<![a-z0-9])\\d{1,2}x\\d{2,3}(?!\\d)");

    private final AppConfigService configService;
    private final MediaProperties properties;
    private final IgnoredFolders ignoredFolders;
    private final SampleFiles sampleFiles;
    private final MediaFileRepository repository;
    private final ProcessingQueue queue;
    private final ProcessingGateService gate;

    /** Cleanup right after {@code movedFile} was moved out of its folder. */
    public void cleanup(Path movedFile, List<ProcessingNote> notes) {
        if (!isEnabled()) return;
        Path sourceRoot = sourceRoot();
        if (sourceRoot == null) return;
        Path folder = movedFile.toAbsolutePath().normalize().getParent();

        // Never clean up the source root itself or anything outside it
        if (folder == null || folder.equals(sourceRoot) || !folder.startsWith(sourceRoot)) return;
        if (ignoredFolders.isIgnored(folder)) return;

        if (!cleanFolder(folder, notes, false)) return;
        for (Path dir = folder.getParent();
                dir != null && !dir.equals(sourceRoot) && dir.startsWith(sourceRoot) && !ignoredFolders.isIgnored(dir);
                dir = dir.getParent()) {
            if (!deleteIfEmpty(dir, notes)) break;
        }
    }

    /**
     * Cleans top-level folders of the source root that were left behind — by older versions, by
     * files removed outside MediaHandler, or by a cleanup that was skipped while an archive was
     * still there.
     */
    @Scheduled(fixedDelayString = "${media.cleanup-interval-ms:1800000}", initialDelay = 120_000)
    public void sweepStaleFolders() {
        if (!isEnabled() || !gate.isRunning() || configService.needsSetup()) return;
        Path sourceRoot = sourceRoot();
        if (sourceRoot == null || !Files.isDirectory(sourceRoot)) return;

        List<Path> folders;
        try (Stream<Path> stream = Files.list(sourceRoot)) {
            folders = stream.filter(Files::isDirectory).filter(dir -> !ignoredFolders.isIgnored(dir)).toList();
        } catch (IOException e) {
            log.warn("Stale folder sweep: could not list '{}': {}", sourceRoot, e.getMessage());
            return;
        }
        Instant staleBefore = Instant.now().minus(Duration.ofHours(staleHours()));
        for (Path folder : folders) {
            // Only folders MediaHandler has dealt with — never a download it has never seen
            if (!repository.existsBySourcePathStartingWith(folder + "/")) continue;
            try {
                if (lastChange(folder).isAfter(staleBefore)) continue;
            } catch (IOException e) {
                log.warn("Stale folder sweep: could not inspect '{}': {}", folder, e.getMessage());
                continue;
            }
            List<ProcessingNote> notes = new ArrayList<>();
            cleanFolder(folder, notes, true);
            notes.forEach(n -> log.info("Stale folder sweep: {} {}", n.step(), n.detail()));
        }
    }

    public long smallVideoMaxMb() {
        return Math.max(0, configService.getInt(
                AppConfigService.FOLDER_CLEANUP_SMALL_VIDEO_MAX_MB, DEFAULT_SMALL_VIDEO_MAX_MB));
    }

    public long staleHours() {
        return Math.max(1, configService.getInt(AppConfigService.FOLDER_CLEANUP_STALE_HOURS, DEFAULT_STALE_HOURS));
    }

    /**
     * Cleans {@code folder} and its subfolders. With {@code onlyIfFinished}, nothing is touched
     * unless every video file in it would be deleted (i.e. nothing is left to process).
     * Returns true if the folder itself was removed.
     */
    private boolean cleanFolder(Path folder, List<ProcessingNote> notes, boolean onlyIfFinished) {
        List<Path> files;
        try (Stream<Path> stream = Files.walk(folder)) {
            files = stream.filter(Files::isRegularFile).filter(p -> !ignoredFolders.isIgnored(p)).toList();
        } catch (IOException e) {
            log.warn("Could not list folder for cleanup '{}': {}", folder, e.getMessage());
            return false;
        }

        // If any archive or partial file is present the folder may still be downloading — skip cleanup entirely
        for (Path file : files) {
            if (ARCHIVE_EXTENSIONS.contains(extension(file))) {
                log.info("Skipping folder cleanup for '{}': archive or partial download present ({})", folder, file);
                return false;
            }
        }

        Set<String> videoExtensions = Set.copyOf(properties.getFileExtensions());
        long smallVideoMaxBytes = smallVideoMaxMb() * 1024 * 1024;
        List<Path> toDelete = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        for (Path file : files) {
            if (!videoExtensions.contains(extension(file))) {
                toDelete.add(file);
                reasons.add(null);
                continue;
            }
            try {
                long size = Files.size(file);
                String keep = keepReason(file, size, smallVideoMaxBytes);
                if (keep != null) {
                    if (onlyIfFinished) {
                        log.debug("Stale folder sweep: keeping '{}' — {}", folder, keep);
                        return false;
                    }
                    log.info("Keeping video file during folder cleanup ({}): {}", keep, file);
                    continue;
                }
                toDelete.add(file);
                reasons.add("%s \"%s\" (%s)".formatted(sampleFiles.isSample(file, size) ? "sample file" : "small video file",
                        folder.relativize(file), formatSize(size)));
            } catch (IOException e) {
                log.warn("Could not inspect file '{}': {}", file, e.getMessage());
                if (onlyIfFinished) return false;
            }
        }

        for (int i = 0; i < toDelete.size(); i++) {
            Path file = toDelete.get(i);
            try {
                Files.deleteIfExists(file);
                if (reasons.get(i) == null) {
                    log.info("Deleted meta file during folder cleanup: {}", file);
                } else {
                    log.info("Deleted {} during folder cleanup", reasons.get(i));
                    notes.add(new ProcessingNote("FOLDER_CLEANUP", "deleted " + reasons.get(i)));
                }
            } catch (IOException e) {
                log.warn("Could not clean up file '{}': {}", file, e.getMessage());
            }
        }

        // Remove folders left empty, deepest first
        try (Stream<Path> stream = Files.walk(folder)) {
            stream.filter(Files::isDirectory)
                    .filter(dir -> !ignoredFolders.isIgnored(dir))
                    .sorted(Comparator.reverseOrder())
                    .forEach(dir -> deleteIfEmpty(dir, notes));
        } catch (IOException e) {
            log.warn("Could not list folder for cleanup '{}': {}", folder, e.getMessage());
        }
        return !Files.exists(folder);
    }

    /** Why a video file must be kept, or null if it is a leftover that can be deleted. */
    private String keepReason(Path file, long size, long smallVideoMaxBytes) {
        if (queue.isActive(file.toString())) return "queued for processing";
        MediaFileStatus status = repository.findTopBySourcePathOrderByIdDesc(file.toString())
                .map(MediaFileRecord::getStatus).orElse(null);
        if (status != null && OPEN_STATUSES.contains(status)) return "record is " + status;
        if (sampleFiles.isSample(file, size)) return null;
        if (size >= smallVideoMaxBytes) return "not yet processed";
        if (isEpisode(file)) return "small episode, not yet processed";
        return null;
    }

    private static Instant lastChange(Path folder) throws IOException {
        try (Stream<Path> stream = Files.walk(folder)) {
            Instant latest = Instant.EPOCH;
            for (Path p : (Iterable<Path>) stream::iterator) {
                Instant modified = Files.getLastModifiedTime(p).toInstant();
                if (modified.isAfter(latest)) latest = modified;
            }
            return latest;
        }
    }

    private boolean isEnabled() {
        return Boolean.parseBoolean(configService.getOrDefault(AppConfigService.FOLDER_CLEANUP_ENABLED, "true"));
    }

    private Path sourceRoot() {
        String value = configService.getOrDefault(AppConfigService.SOURCE_FOLDER, properties.getSourceFolder());
        return StringUtils.isBlank(value) ? null : Paths.get(value).toAbsolutePath().normalize();
    }

    private boolean deleteIfEmpty(Path dir, List<ProcessingNote> notes) {
        try {
            Files.delete(dir);
            log.info("Deleted source folder: {}", dir);
            notes.add(new ProcessingNote("FOLDER_DELETED", dir.toString()));
            return true;
        } catch (DirectoryNotEmptyException e) {
            log.debug("Keeping source folder '{}': not empty", dir);
        } catch (IOException e) {
            log.warn("Could not delete source folder '{}': {}", dir, e.getMessage());
        }
        return false;
    }

    static boolean isEpisode(Path file) {
        return EPISODE_MARKER.matcher(file.getFileName().toString()).find();
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

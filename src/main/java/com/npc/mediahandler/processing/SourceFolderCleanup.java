package com.npc.mediahandler.processing;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.npc.mediahandler.config.AppConfigService;
import com.npc.mediahandler.config.MediaProperties;
import com.npc.mediahandler.monitor.IgnoredFolders;
import com.npc.mediahandler.monitor.SampleFiles;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Cleans up the release folder a file was moved out of. The folder and all of its subfolders are
 * scanned:
 * <ul>
 *   <li>non-video files (.nfo, .jpg, .srt, ...) are deleted</li>
 *   <li>video files below {@code folder.cleanup.small-video-max-mb} and sample files are deleted —
 *       except small files with an episode marker (S01E03, 1x03), which are unprocessed episodes
 *       of a season pack rather than extras</li>
 *   <li>larger video files are kept (still to be processed)</li>
 *   <li>every folder left empty is removed, then empty parent folders up to the source root</li>
 * </ul>
 * Nothing is touched while an archive is present (download may still be extracting), and ignored
 * folders are never entered or removed.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SourceFolderCleanup {

    public static final int DEFAULT_SMALL_VIDEO_MAX_MB = 200;

    /** Archive/compressed formats that indicate an active or pending download — never delete these or their folder. */
    private static final Set<String> ARCHIVE_EXTENSIONS = Set.of(
            "zip", "rar", "7z", "gz", "bz2", "xz", "tar",
            "r00", "r01", "r02", "r03", "r04", "r05",
            "001", "002", "003",
            "nzb", "par", "par2"
    );

    private static final Pattern EPISODE_MARKER = Pattern.compile(
            "(?i)(?<![a-z0-9])s\\d{1,2}[ ._-]*e\\d{1,3}|(?<![a-z0-9])\\d{1,2}x\\d{2,3}(?!\\d)");

    private final AppConfigService configService;
    private final MediaProperties properties;
    private final IgnoredFolders ignoredFolders;
    private final SampleFiles sampleFiles;

    public void cleanup(Path movedFile, List<ProcessingNote> notes) {
        boolean enabled = Boolean.parseBoolean(
                configService.getOrDefault(AppConfigService.FOLDER_CLEANUP_ENABLED, "true"));
        if (!enabled) return;

        String sourceRootValue = configService.getOrDefault(
                AppConfigService.SOURCE_FOLDER, properties.getSourceFolder());
        if (StringUtils.isBlank(sourceRootValue)) return;
        Path sourceRoot = Paths.get(sourceRootValue).toAbsolutePath().normalize();
        Path folder = movedFile.toAbsolutePath().normalize().getParent();

        // Never clean up the source root itself or anything outside it
        if (folder == null || folder.equals(sourceRoot) || !folder.startsWith(sourceRoot)) return;
        if (ignoredFolders.isIgnored(folder)) return;

        List<Path> files;
        try (Stream<Path> stream = Files.walk(folder)) {
            files = stream.filter(Files::isRegularFile).filter(p -> !ignoredFolders.isIgnored(p)).toList();
        } catch (IOException e) {
            log.warn("Could not list folder for cleanup '{}': {}", folder, e.getMessage());
            return;
        }

        // If any archive file is present the folder may still be downloading — skip cleanup entirely
        for (Path file : files) {
            if (ARCHIVE_EXTENSIONS.contains(extension(file))) {
                log.info("Skipping folder cleanup for '{}': archive file present ({})", folder, file);
                return;
            }
        }

        Set<String> videoExtensions = Set.copyOf(properties.getFileExtensions());
        long smallVideoMaxBytes = smallVideoMaxMb() * 1024 * 1024;
        for (Path file : files) {
            try {
                if (!videoExtensions.contains(extension(file))) {
                    Files.deleteIfExists(file);
                    log.info("Deleted meta file during folder cleanup: {}", file);
                    continue;
                }
                long size = Files.size(file);
                boolean sample = sampleFiles.isSample(file, size);
                boolean smallExtra = size < smallVideoMaxBytes && !isEpisode(file);
                if (sample || smallExtra) {
                    Files.deleteIfExists(file);
                    String sizeStr = formatSize(size);
                    log.info("Deleted {} during folder cleanup: {} ({})",
                            sample ? "sample file" : "small video file", file, sizeStr);
                    notes.add(new ProcessingNote("FOLDER_CLEANUP", "deleted %s \"%s\" (%s)".formatted(
                            sample ? "sample file" : "small video file", folder.relativize(file), sizeStr)));
                } else {
                    log.info("Keeping video file during folder cleanup (not yet processed): {}", file);
                }
            } catch (IOException e) {
                log.warn("Could not clean up file '{}': {}", file, e.getMessage());
            }
        }

        // Remove folders left empty, deepest first, then empty parents up to the source root
        try (Stream<Path> stream = Files.walk(folder)) {
            stream.filter(Files::isDirectory)
                    .filter(dir -> !ignoredFolders.isIgnored(dir))
                    .sorted(Comparator.reverseOrder())
                    .forEach(dir -> deleteIfEmpty(dir, notes));
        } catch (IOException e) {
            log.warn("Could not list folder for cleanup '{}': {}", folder, e.getMessage());
            return;
        }
        for (Path dir = folder.getParent();
                dir != null && !dir.equals(sourceRoot) && dir.startsWith(sourceRoot) && !ignoredFolders.isIgnored(dir);
                dir = dir.getParent()) {
            if (!deleteIfEmpty(dir, notes)) break;
        }
    }

    public long smallVideoMaxMb() {
        return Math.max(0, configService.getInt(
                AppConfigService.FOLDER_CLEANUP_SMALL_VIDEO_MAX_MB, DEFAULT_SMALL_VIDEO_MAX_MB));
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

package com.npc.mediahandler.monitor;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.npc.mediahandler.config.AppConfigService;
import com.npc.mediahandler.config.MediaProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Folders inside the source folder that MediaHandler must never touch: not scanned,
 * processed, renamed, cleaned up or deleted. Configured in {@code source.ignored.folders}
 * as a comma- or newline-separated list of paths relative to the source folder
 * (absolute paths also work).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IgnoredFolders {

    private final AppConfigService configService;
    private final MediaProperties properties;

    /** Absolute, normalised paths of the configured ignored folders. */
    public List<Path> resolve() {
        String sourceRoot = configService.getOrDefault(AppConfigService.SOURCE_FOLDER, properties.getSourceFolder());
        String raw = configService.getOrDefault(AppConfigService.SOURCE_IGNORED_FOLDERS, "");
        List<Path> result = new ArrayList<>();
        for (String entry : StringUtils.split(raw, ",\n")) {
            entry = StringUtils.strip(entry);
            if (entry.isEmpty()) continue;
            try {
                Path p = Paths.get(entry);
                if (!p.isAbsolute()) {
                    if (StringUtils.isBlank(sourceRoot)) continue;
                    p = Paths.get(sourceRoot).resolve(p);
                }
                result.add(p.toAbsolutePath().normalize());
            } catch (InvalidPathException e) {
                log.warn("Ignoring invalid entry in {}: '{}'", AppConfigService.SOURCE_IGNORED_FOLDERS, entry);
            }
        }
        return result;
    }

    /** True if the path is an ignored folder or lies anywhere inside one. */
    public boolean isIgnored(Path path) {
        return isIgnored(path, resolve());
    }

    /**
     * Returns all regular files under {@code root}, never descending into ignored folders.
     * Use instead of {@code Files.walk} for anything that looks at the source folder.
     */
    public List<Path> walkFiles(Path root) throws IOException {
        List<Path> ignored = resolve();
        List<Path> files = new ArrayList<>();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (isIgnored(dir, ignored)) {
                    log.debug("Skipping ignored folder: {}", dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isRegularFile()) files.add(file);
                return FileVisitResult.CONTINUE;
            }
        });
        return files;
    }

    private static boolean isIgnored(Path path, List<Path> ignored) {
        Path normalized = path.toAbsolutePath().normalize();
        return ignored.stream().anyMatch(normalized::startsWith);
    }
}

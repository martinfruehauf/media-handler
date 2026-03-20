package com.npc.mediahandler.processing;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.npc.mediahandler.config.AppConfigService;
import com.npc.mediahandler.config.MediaProperties;
import com.npc.mediahandler.media.MediaMetadata;
import com.npc.mediahandler.tmdb.TmdbResult;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class FileRenameService {

    private final AppConfigService configService;
    private final MediaProperties properties;

    public Optional<Path> process(Path source, MediaMetadata metadata, TmdbResult tmdbResult) throws IOException {
        String ext = FilenameUtils.getExtension(source.getFileName().toString());
        Path targetFile;

        if (metadata.isMovie()) {
            Path targetFolder = Paths.get(configService.getOrDefault(
                    AppConfigService.TARGET_FOLDER_MOVIES, properties.getTargetFolderMovies()));
            targetFile = targetFolder.resolve(
                    "%s (%s).%s".formatted(tmdbResult.name(), tmdbResult.year(), ext));
        } else {
            Path targetFolder = Paths.get(configService.getOrDefault(
                    AppConfigService.TARGET_FOLDER_SHOWS, properties.getTargetFolderShows()));
            String seasonPadded = StringUtils.leftPad(
                    StringUtils.removeStartIgnoreCase(metadata.season(), "S"), 2, '0');
            Path seasonPath = targetFolder
                    .resolve("%s (%s)".formatted(tmdbResult.name(), tmdbResult.year()))
                    .resolve("Season " + seasonPadded);
            Files.createDirectories(seasonPath);
            targetFile = seasonPath.resolve(
                    "%s (%s) - %s%s.%s".formatted(
                            tmdbResult.name(), tmdbResult.year(),
                            metadata.season(), metadata.episode(), ext));
        }

        if (Files.exists(targetFile)) {
            boolean overwrite = Boolean.parseBoolean(
                configService.getOrDefault(AppConfigService.FILE_OVERWRITE, "false"));
            if (!overwrite) {
                log.info("Target already exists, skipping (overwrite disabled): {}", targetFile);
                return Optional.empty();
            }
        }

        Files.move(source, targetFile, REPLACE_EXISTING);
        log.info("Moved: {} → {}", source, targetFile);
        return Optional.of(targetFile);
    }
}

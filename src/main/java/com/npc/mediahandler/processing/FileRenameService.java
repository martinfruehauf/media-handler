package com.npc.mediahandler.processing;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

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

    public Path process(Path source, MediaMetadata metadata, TmdbResult tmdbResult) throws IOException {
        Path targetFolder = Paths.get(configService.getOrDefault(
                AppConfigService.TARGET_FOLDER, properties.getTargetFolder()));

        String ext = FilenameUtils.getExtension(source.getFileName().toString());
        Path targetFile;

        if (metadata.isMovie()) {
            targetFile = targetFolder.resolve(
                    "%s (%s).%s".formatted(tmdbResult.name(), tmdbResult.year(), ext));
        } else {
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

        Files.move(source, targetFile, REPLACE_EXISTING);
        log.info("Moved: {} → {}", source, targetFile);
        return targetFile;
    }
}

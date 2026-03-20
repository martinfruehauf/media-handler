package com.npc.mediahandler.processing;

import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.npc.mediahandler.config.MediaProperties;
import com.npc.mediahandler.media.MediaMetadata;
import com.npc.mediahandler.tmdb.TmdbResult;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class FileRenameService {

    private final Path targetFolder;

    public FileRenameService(MediaProperties properties) {
        this.targetFolder = Paths.get(properties.getTargetFolder());
    }

    /**
     * Renames and moves {@code source} to its canonical location under the target folder.
     *
     * @return the path the file was moved to
     * @throws IOException if directory creation or the move operation fails
     */
    public Path process(Path source, MediaMetadata metadata, TmdbResult tmdbResult) throws IOException {
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

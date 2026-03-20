package com.npc.mediahandler.rest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.npc.mediahandler.config.AppConfigService;
import com.npc.mediahandler.config.MediaProperties;
import com.npc.mediahandler.processing.MediaFileRecord;
import com.npc.mediahandler.processing.MediaFileRepository;
import com.npc.mediahandler.processing.MediaFileStatus;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/source-files")
@RequiredArgsConstructor
public class SourceFilesController {

    private final AppConfigService configService;
    private final MediaProperties properties;
    private final MediaFileRepository repository;

    @GetMapping
    public List<SourceFileDto> list() {
        String folderPath = configService.getOrDefault(AppConfigService.SOURCE_FOLDER,
                properties.getSourceFolder());
        if (folderPath == null) return Collections.emptyList();

        Path sourceFolder = Paths.get(folderPath);
        if (!Files.isDirectory(sourceFolder)) return Collections.emptyList();

        try (Stream<Path> walk = Files.walk(sourceFolder)) {
            return walk.filter(Files::isRegularFile)
                    .filter(f -> isMediaFile(f.getFileName().toString()))
                    .map(file -> {
                        long size = 0;
                        try { size = Files.size(file); } catch (IOException ignored) {}
                        MediaFileRecord record = repository.findBySourcePath(file.toString()).orElse(null);
                        return new SourceFileDto(
                                file.getFileName().toString(),
                                file.toString(),
                                size,
                                record != null ? record.getStatus() : null,
                                record != null ? record.getErrorMessage() : null,
                                record != null ? record.getRetryCount() : 0,
                                record != null ? record.getId() : null
                        );
                    })
                    .sorted((a, b) -> a.filename().compareToIgnoreCase(b.filename()))
                    .toList();
        } catch (IOException e) {
            log.error("Failed to list source folder: {}", sourceFolder, e);
            return Collections.emptyList();
        }
    }

    private boolean isMediaFile(String name) {
        String lower = name.toLowerCase();
        return properties.getFileExtensions().stream().anyMatch(ext -> lower.endsWith("." + ext));
    }
}

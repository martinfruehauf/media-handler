package com.npc.mediahandler.rest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.npc.mediahandler.config.AppConfigService;
import com.npc.mediahandler.config.MediaProperties;
import com.npc.mediahandler.processing.FileProcessingService;
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
    private final FileProcessingService fileProcessingService;

    record RenameRequest(String from, String newName) {}

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
                        MediaFileRecord record = repository.findTopBySourcePathOrderByIdDesc(file.toString())
                                .filter(r -> r.getStatus() != MediaFileStatus.MOVED)
                                .orElse(null);
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

    @PostMapping("/rescan")
    public ResponseEntity<Map<String, Integer>> rescan() {
        String folderPath = configService.getOrDefault(AppConfigService.SOURCE_FOLDER,
                properties.getSourceFolder());
        if (folderPath == null) return ResponseEntity.ok(Map.of("queued", 0));

        Path sourceFolder = Paths.get(folderPath);
        if (!Files.isDirectory(sourceFolder)) return ResponseEntity.ok(Map.of("queued", 0));

        List<MediaFileRecord> toProcess = new java.util.ArrayList<>();
        try (Stream<Path> walk = Files.walk(sourceFolder)) {
            walk.filter(Files::isRegularFile)
                    .filter(f -> isMediaFile(f.getFileName().toString()))
                    .forEach(file -> {
                        var latest = repository.findTopBySourcePathOrderByIdDesc(file.toString());
                        // Skip files already being processed or successfully moved
                        if (latest.isPresent()) {
                            MediaFileStatus s = latest.get().getStatus();
                            if (s == MediaFileStatus.PENDING || s == MediaFileStatus.MOVED) return;
                            // Reset existing failed/skipped record
                            MediaFileRecord r = latest.get();
                            r.setStatus(MediaFileStatus.PENDING);
                            r.setErrorMessage(null);
                            toProcess.add(repository.save(r));
                        } else {
                            // No record yet — create one and process immediately
                            toProcess.add(repository.save(MediaFileRecord.builder()
                                    .originalFilename(file.getFileName().toString())
                                    .sourcePath(file.toString())
                                    .status(MediaFileStatus.PENDING)
                                    .createdAt(Instant.now())
                                    .retryCount(0)
                                    .build()));
                        }
                    });
        } catch (IOException e) {
            log.error("Rescan failed", e);
            return ResponseEntity.internalServerError().body(Map.of("queued", 0));
        }

        CompletableFuture.runAsync(() -> toProcess.forEach(fileProcessingService::execute));
        log.info("Rescan queued {} file(s) for processing", toProcess.size());
        return ResponseEntity.ok(Map.of("queued", toProcess.size()));
    }

    @PostMapping("/rename")
    public ResponseEntity<Map<String, String>> rename(@RequestBody RenameRequest req) {
        Path from = Paths.get(req.from());
        if (!Files.isRegularFile(from)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Source file not found"));
        }
        Path to = from.getParent().resolve(req.newName());
        if (!to.getParent().toAbsolutePath().equals(from.getParent().toAbsolutePath())) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot rename to a different directory"));
        }
        if (!isMediaFile(to.getFileName().toString())) {
            return ResponseEntity.badRequest().body(Map.of("error", "New name must keep a supported media extension"));
        }
        try {
            Files.move(from, to);
        } catch (IOException e) {
            log.error("Rename failed: {} → {}", from, to, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
        MediaFileRecord record = repository.save(MediaFileRecord.builder()
                .originalFilename(to.getFileName().toString())
                .sourcePath(to.toString())
                .status(MediaFileStatus.PENDING)
                .createdAt(Instant.now())
                .retryCount(0)
                .build());
        CompletableFuture.runAsync(() -> fileProcessingService.execute(record));
        log.info("Renamed '{}' → '{}', queued for processing", from.getFileName(), to.getFileName());
        return ResponseEntity.ok(Map.of("newPath", to.toString(), "newName", to.getFileName().toString()));
    }

    private boolean isMediaFile(String name) {
        String lower = name.toLowerCase();
        return properties.getFileExtensions().stream().anyMatch(ext -> lower.endsWith("." + ext));
    }
}

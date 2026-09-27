package com.npc.mediahandler.rest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.npc.mediahandler.processing.FileProcessingService;
import com.npc.mediahandler.processing.MediaFileRecord;
import com.npc.mediahandler.processing.MediaFileRepository;
import com.npc.mediahandler.processing.MediaFileStatus;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/records")
@RequiredArgsConstructor
public class RecordsController {

    private final MediaFileRepository repository;
    private final FileProcessingService fileProcessingService;

    @GetMapping
    public List<MediaFileRecord> list(@RequestParam(required = false) MediaFileStatus status) {
        Sort sort = Sort.by(Sort.Direction.DESC, "createdAt");
        if (status != null) {
            return repository.findAll(sort).stream()
                    .filter(r -> r.getStatus() == status)
                    .toList();
        }
        return repository.findAll(sort);
    }

    @GetMapping("/{id}")
    public ResponseEntity<MediaFileRecord> get(@PathVariable Long id) {
        return repository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/retry-failed")
    public ResponseEntity<Map<String, Integer>> retryFailed() {
        var failedStatuses = List.of(
                MediaFileStatus.LLM_FAILED, MediaFileStatus.TMDB_FAILED, MediaFileStatus.MOVE_FAILED);
        var toRetry = repository.findLatestByStatus(failedStatuses).stream()
                .filter(r -> Files.exists(Path.of(r.getSourcePath())))
                .filter(r -> !fileProcessingService.isQueued(r.getSourcePath()))
                .toList();
        toRetry.forEach(this::queue);
        return ResponseEntity.ok(Map.of("queued", toRetry.size()));
    }

    @PostMapping("/{id}/skip")
    public ResponseEntity<Void> skip(@PathVariable Long id) {
        return repository.findById(id).map(r -> {
            r.setStatus(MediaFileStatus.SKIPPED);
            r.setErrorMessage("Manually excluded");
            repository.save(r);
            return ResponseEntity.noContent().<Void>build();
        }).orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/unskip")
    public ResponseEntity<Void> unskip(@PathVariable Long id) {
        return repository.findById(id)
                .map(this::queueOrConflict)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<Void> retry(@PathVariable Long id) {
        return repository.findById(id)
                .filter(r -> Files.exists(Path.of(r.getSourcePath())))
                .map(this::queueOrConflict)
                .orElse(ResponseEntity.notFound().build());
    }

    /** 409 if the file is already queued or being processed — its record is left as it is. */
    private ResponseEntity<Void> queueOrConflict(MediaFileRecord r) {
        if (fileProcessingService.isQueued(r.getSourcePath())) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
        queue(r);
        return ResponseEntity.noContent().build();
    }

    private void queue(MediaFileRecord r) {
        r.setStatus(MediaFileStatus.PENDING);
        r.setErrorMessage(null);
        repository.save(r);
        fileProcessingService.submit(r);
    }

    @DeleteMapping
    public ResponseEntity<Void> deleteAll() {
        repository.deleteAll();
        return ResponseEntity.noContent().build();
    }
}

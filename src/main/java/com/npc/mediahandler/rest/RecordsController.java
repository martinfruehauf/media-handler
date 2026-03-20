package com.npc.mediahandler.rest;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.npc.mediahandler.processing.MediaFileRecord;
import com.npc.mediahandler.processing.MediaFileRepository;
import com.npc.mediahandler.processing.MediaFileStatus;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/records")
@RequiredArgsConstructor
public class RecordsController {

    private final MediaFileRepository repository;

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
}

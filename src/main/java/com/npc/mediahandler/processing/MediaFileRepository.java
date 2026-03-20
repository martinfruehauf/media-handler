package com.npc.mediahandler.processing;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MediaFileRepository extends JpaRepository<MediaFileRecord, Long> {

    Optional<MediaFileRecord> findBySourcePath(String sourcePath);

    List<MediaFileRecord> findByStatusInAndRetryCountLessThan(List<MediaFileStatus> statuses, int maxRetries);
}

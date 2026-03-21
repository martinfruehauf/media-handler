package com.npc.mediahandler.processing;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MediaFileRepository extends JpaRepository<MediaFileRecord, Long> {

    /** Latest record for a given source path (highest id = most recently created). */
    Optional<MediaFileRecord> findTopBySourcePathOrderByIdDesc(String sourcePath);

    /**
     * Returns retryable records, but only the latest (highest id) per source path.
     * This prevents the retry service from re-running superseded historical records.
     */
    @Query("""
            SELECT r FROM MediaFileRecord r
            WHERE r.status IN :statuses
              AND r.retryCount < :maxRetries
              AND r.id = (SELECT MAX(r2.id) FROM MediaFileRecord r2 WHERE r2.sourcePath = r.sourcePath)
            """)
    List<MediaFileRecord> findLatestRetryable(
            @Param("statuses") List<MediaFileStatus> statuses,
            @Param("maxRetries") int maxRetries);

    /** Records whose source file is due for deletion. */
    List<MediaFileRecord> findBySourceDeleteAfterBefore(Instant cutoff);
}

package com.npc.mediahandler.processing;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "media_file_record")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MediaFileRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 1024)
    private String originalFilename;

    @Column(nullable = false, length = 1024)
    private String sourcePath;

    @Column(length = 1024)
    private String targetPath;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MediaFileStatus status;

    @Column(length = 2000)
    private String errorMessage;

    /** Total number of processing attempts made (1 after first try, 2 after first retry, …). */
    private int retryCount;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant lastAttemptAt;

    /** Set only when status reaches MOVED. */
    private Instant processedAt;

    /** JSON array of ProcessingNote, nullable. Populated after each pipeline run. */
    @Column(columnDefinition = "TEXT")
    private String processingNotes;

    /**
     * When set, the cleanup scheduler will delete the source file at this time.
     * Only populated when copy mode is active and a delete-after delay is configured.
     */
    private Instant sourceDeleteAfter;
}

package com.npc.mediahandler.rest;

import com.npc.mediahandler.processing.MediaFileStatus;

public record SourceFileDto(
        String filename,
        String path,
        long sizeBytes,
        MediaFileStatus status,
        String errorMessage,
        int retryCount,
        Long recordId
) {}

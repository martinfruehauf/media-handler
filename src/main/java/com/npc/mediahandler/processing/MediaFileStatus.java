package com.npc.mediahandler.processing;

public enum MediaFileStatus {
    PENDING,
    LLM_FAILED,
    TMDB_FAILED,
    MOVE_FAILED,
    MOVED,
    SKIPPED
}

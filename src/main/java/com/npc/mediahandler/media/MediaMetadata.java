package com.npc.mediahandler.media;

public record MediaMetadata(
        String type,
        String name,
        String year,
        String season,
        String episode,
        String error
) {
    public boolean isError() {
        return error != null && !error.isBlank();
    }

    public boolean isMovie() {
        return "movie".equalsIgnoreCase(type);
    }

    public boolean isShow() {
        return "show".equalsIgnoreCase(type);
    }
}

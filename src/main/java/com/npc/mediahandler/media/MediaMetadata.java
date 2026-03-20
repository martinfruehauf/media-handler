package com.npc.mediahandler.media;

import org.apache.commons.lang3.StringUtils;

public record MediaMetadata(
        String type,
        String name,
        String year,
        String season,
        String episode,
        String error
) {
    public boolean isError() {
        return StringUtils.isNotBlank(error);
    }

    public boolean isMovie() {
        return "movie".equalsIgnoreCase(type);
    }

    public boolean isShow() {
        return "show".equalsIgnoreCase(type);
    }
}

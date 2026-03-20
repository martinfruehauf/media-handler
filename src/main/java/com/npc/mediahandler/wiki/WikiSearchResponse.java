package com.npc.mediahandler.wiki;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WikiSearchResponse(Query query) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Query(List<SearchResult> search) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SearchResult(String title) {}
}

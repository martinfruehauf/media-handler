package com.npc.mediahandler.wiki;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonIgnoreProperties(ignoreUnknown = true)
public record WikiLangLinksResponse(Query query) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Query(Map<String, Page> pages) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Page(List<LangLink> langlinks) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LangLink(String lang, @JsonProperty("*") String title) {}
}

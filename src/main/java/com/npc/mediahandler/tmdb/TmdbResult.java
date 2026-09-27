package com.npc.mediahandler.tmdb;

/**
 * First (highest-ranked) TMDB search hit. {@code name} is the en-US title used for renaming;
 * {@code originalName}/{@code originalLanguage} are the title in the original language.
 */
public record TmdbResult(String name, String year, String tmdbId, String originalName, String originalLanguage) {}

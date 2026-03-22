package com.npc.mediahandler.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Data;

@Data
@ConfigurationProperties(prefix = "media")
public class MediaProperties {

    /** Folder that is scanned for new media files. */
    private String sourceFolder;

    /** Root folder where renamed movie files will be moved to. */
    private String targetFolderMovies;

    /** Root folder where renamed TV show files will be moved to. */
    private String targetFolderShows;

    /** File extensions (without dot) to consider as media files. */
    private List<String> fileExtensions = List.of("mkv", "mp4", "avi", "m4v", "mov", "wmv");

    /** How often (milliseconds) the source folder is polled to check file sizes. */
    private long pollIntervalMs = 30_000;

    /**
     * How long (seconds) a file size must remain unchanged before the file is
     * considered fully extracted and ready to process.
     */
    private long stabilityThresholdSeconds = 60;

    /**
     * Files below this size (MB) that match a video extension are treated as sample/junk and deleted during
     * source-folder cleanup.
     */
    private long sampleVideoThresholdMb = 50;

    /** How often (milliseconds) to check for source files that are due for deletion in copy mode. */
    private long cleanupIntervalMs = 30 * 60 * 1000;

    private Tmdb tmdb = new Tmdb();

    @Data
    public static class Tmdb {
        private String apiKey;
        private String baseUrl = "https://api.themoviedb.org/3";
    }

    private Retry retry = new Retry();

    @Data
    public static class Retry {
        /** Whether automatic retry of failed records is enabled. */
        private boolean enabled = false;
        /** How often (ms) to scan for failed records and retry them. */
        private long intervalMs = 300_000;
        /** Maximum total attempts before a record is abandoned. */
        private int maxAttempts = 5;
    }
}

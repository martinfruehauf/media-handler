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

    /**
     * Folders inside the source folder that are never scanned, processed or deleted
     * (relative to the source folder, or absolute). Seed for the {@code source.ignored.folders} setting.
     */
    private List<String> ignoredFolders = List.of("usenet");

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

    private Llm llm = new Llm();

    @Data
    public static class Llm {
        /**
         * "local" — llama-server in this container, started on demand (default for fresh installs);
         * "remote" — the OpenAI-compatible / Anthropic endpoint configured under llm.provider etc.
         */
        private String mode = "local";
        private Local local = new Local();
    }

    @Data
    public static class Local {
        /** Path to the llama.cpp llama-server binary. */
        private String serverBinary = "/opt/llama.cpp/llama-server";
        /** Path to the GGUF model file. */
        private String modelPath = "/opt/mediahandler/models/qwen2.5-1.5b-instruct-q4_k_m.gguf";
        /** Model name (llama-server --alias); sent as "model" in requests and shown in logs. */
        private String modelName = "qwen2.5-1.5b-instruct";
        /** CPU threads for inference — set to the container's core count. */
        private int threads = 4;
        /** Port llama-server listens on (bound to 127.0.0.1 only). */
        private int port = 8081;
        /** Context size in tokens. The system prompt plus one filename needs well under 2048. */
        private int ctxSize = 2048;
        /** Stop llama-server after this many idle seconds; 0 keeps it running once started. */
        private int idleTimeoutSeconds = 600;
        /** How long to wait for llama-server to load the model and report healthy. */
        private int startupTimeoutSeconds = 120;
        /** Extra command-line arguments appended to the llama-server invocation. */
        private String extraArgs = "";
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

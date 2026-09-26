package com.npc.mediahandler.config;

import java.util.Map;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class AppConfigService {

    @Value("${spring.ai.openai.api-key:ollama}")
    private String openAiApiKey;

    @Value("${spring.ai.openai.base-url:http://localhost:11434}")
    private String openAiBaseUrl;

    @Value("${spring.ai.openai.chat.options.model:qwen2.5:14b}")
    private String openAiModel;

    public static final String SOURCE_FOLDER          = "source.folder";
    public static final String SOURCE_IGNORED_FOLDERS = "source.ignored.folders";
    public static final String TARGET_FOLDER_MOVIES   = "target.folder.movies";
    public static final String TARGET_FOLDER_SHOWS    = "target.folder.shows";
    public static final String TMDB_API_KEY    = "tmdb.api-key";
    public static final String TMDB_BASE_URL   = "tmdb.base-url";
    public static final String LLM_MODE        = "llm.mode";       // "local" | "remote"
    public static final String LLM_MODE_LOCAL  = "local";
    public static final String LLM_MODE_REMOTE = "remote";
    public static final String LLM_PROVIDER    = "llm.provider";   // "openai" | "anthropic" (remote mode)
    public static final String LLM_API_KEY     = "llm.api-key";
    public static final String LLM_BASE_URL    = "llm.base-url";
    public static final String LLM_MODEL            = "llm.model";
    public static final String LLM_WOL_ENABLED      = "llm.wol.enabled";
    public static final String LLM_WOL_MAC          = "llm.wol.mac";
    public static final String LLM_WOL_SSH_USER     = "llm.wol.ssh-user";
    public static final String LLM_WOL_SHUTDOWN_CMD = "llm.wol.shutdown-cmd";
    public static final String LLM_LOCAL_SERVER_BINARY   = "llm.local.server-binary";
    public static final String LLM_LOCAL_MODEL_PATH      = "llm.local.model-path";
    public static final String LLM_LOCAL_MODEL_NAME      = "llm.local.model-name";
    public static final String LLM_LOCAL_THREADS         = "llm.local.threads";
    public static final String LLM_LOCAL_PORT            = "llm.local.port";
    public static final String LLM_LOCAL_CTX_SIZE        = "llm.local.ctx-size";
    public static final String LLM_LOCAL_IDLE_TIMEOUT    = "llm.local.idle-timeout-seconds";
    public static final String LLM_LOCAL_STARTUP_TIMEOUT = "llm.local.startup-timeout-seconds";
    public static final String LLM_LOCAL_EXTRA_ARGS      = "llm.local.extra-args";
    public static final String FILE_OVERWRITE                  = "file.overwrite";
    public static final String FILE_COPY_MODE                  = "file.copy.mode";
    public static final String FILE_DELETE_ORIGINAL_AFTER_HOURS = "file.delete.original.after.hours";
    public static final String FOLDER_CLEANUP_ENABLED          = "folder.cleanup.enabled";
    public static final String WIKI_TITLE_LOOKUP               = "wiki.title.lookup";

    private final AppConfigRepository repository;
    private final MediaProperties properties;

    @PostConstruct
    void seed() {
        setIfAbsent(SOURCE_FOLDER,        properties.getSourceFolder());
        setIfAbsent(SOURCE_IGNORED_FOLDERS, String.join(",", properties.getIgnoredFolders()));
        setIfAbsent(TARGET_FOLDER_MOVIES, properties.getTargetFolderMovies());
        setIfAbsent(TARGET_FOLDER_SHOWS,  properties.getTargetFolderShows());
        setIfAbsent(TMDB_API_KEY,  properties.getTmdb().getApiKey());
        setIfAbsent(TMDB_BASE_URL, properties.getTmdb().getBaseUrl());
        // Installs that already had an LLM configured before local mode existed keep using the remote endpoint.
        setIfAbsent(LLM_MODE, repository.existsById(LLM_PROVIDER) ? LLM_MODE_REMOTE : properties.getLlm().getMode());
        setIfAbsent(LLM_PROVIDER,  "openai");
        setIfAbsent(LLM_API_KEY,   openAiApiKey);
        setIfAbsent(LLM_BASE_URL,  openAiBaseUrl);
        setIfAbsent(LLM_MODEL,            openAiModel);
        setIfAbsent(LLM_WOL_ENABLED,      "true");
        setIfAbsent(LLM_WOL_MAC,          "b4:a9:fc:cd:58:88");
        setIfAbsent(LLM_WOL_SSH_USER,     "martin");
        setIfAbsent(LLM_WOL_SHUTDOWN_CMD, "");
        MediaProperties.Local local = properties.getLlm().getLocal();
        setIfAbsent(LLM_LOCAL_SERVER_BINARY,   local.getServerBinary());
        setIfAbsent(LLM_LOCAL_MODEL_PATH,      local.getModelPath());
        setIfAbsent(LLM_LOCAL_MODEL_NAME,      local.getModelName());
        setIfAbsent(LLM_LOCAL_THREADS,         String.valueOf(local.getThreads()));
        setIfAbsent(LLM_LOCAL_PORT,            String.valueOf(local.getPort()));
        setIfAbsent(LLM_LOCAL_CTX_SIZE,        String.valueOf(local.getCtxSize()));
        setIfAbsent(LLM_LOCAL_IDLE_TIMEOUT,    String.valueOf(local.getIdleTimeoutSeconds()));
        setIfAbsent(LLM_LOCAL_STARTUP_TIMEOUT, String.valueOf(local.getStartupTimeoutSeconds()));
        setIfAbsent(LLM_LOCAL_EXTRA_ARGS,      local.getExtraArgs());
        setIfAbsent(FILE_OVERWRITE, "false");
        setIfAbsent(FILE_COPY_MODE, "false");
        setIfAbsent(FILE_DELETE_ORIGINAL_AFTER_HOURS, "0");
        setIfAbsent(FOLDER_CLEANUP_ENABLED, "true");
        setIfAbsent(WIKI_TITLE_LOOKUP, "true");
    }

    public String get(String key) {
        return repository.findById(key).map(AppConfig::getValue).orElse(null);
    }

    public String getOrDefault(String key, String defaultValue) {
        String value = get(key);
        return value != null ? value : defaultValue;
    }

    public int getInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(StringUtils.strip(get(key)));
        } catch (NumberFormatException e) {
            return defaultValue;
        }
    }

    public boolean isLocalLlm() {
        return LLM_MODE_LOCAL.equalsIgnoreCase(getOrDefault(LLM_MODE, LLM_MODE_LOCAL));
    }

    /** Returns the configured LLM base URL, falling back to the yml-injected Spring AI value. */
    public String getLlmBaseUrl() {
        return getOrDefault(LLM_BASE_URL, openAiBaseUrl);
    }

    public void set(String key, String value) {
        repository.save(new AppConfig(key, value));
    }

    public Map<String, String> getAll() {
        return repository.findAll().stream()
                .collect(Collectors.toMap(AppConfig::getConfigKey, AppConfig::getValue));
    }

    public static final java.util.Set<String> PLACEHOLDERS = java.util.Set.of(
            "YOUR_TMDB_BEARER_TOKEN", "sk-ant-..."
    );

    public boolean needsSetup() {
        return isBlankOrPlaceholder(get(SOURCE_FOLDER))
            || isBlankOrPlaceholder(get(TARGET_FOLDER_MOVIES))
            || isBlankOrPlaceholder(get(TARGET_FOLDER_SHOWS))
            || isBlankOrPlaceholder(get(TMDB_API_KEY))
            || (!isLocalLlm() && isBlankOrPlaceholder(get(LLM_API_KEY)));
    }

    public static boolean isBlankOrPlaceholder(String v) {
        return v == null || v.isBlank() || PLACEHOLDERS.contains(v);
    }

    private void setIfAbsent(String key, String value) {
        if (value == null || PLACEHOLDERS.contains(value)) return;
        if (!repository.existsById(key)) {
            repository.save(new AppConfig(key, value));
        } else {
            // Overwrite if the stored value is still a placeholder
            String stored = get(key);
            if (PLACEHOLDERS.contains(stored)) {
                repository.save(new AppConfig(key, value));
            }
        }
    }
}

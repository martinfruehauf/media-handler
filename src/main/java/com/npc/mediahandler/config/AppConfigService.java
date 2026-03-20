package com.npc.mediahandler.config;

import java.util.Map;
import java.util.stream.Collectors;

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

    public static final String SOURCE_FOLDER   = "source.folder";
    public static final String TARGET_FOLDER   = "target.folder";
    public static final String TMDB_API_KEY    = "tmdb.api-key";
    public static final String TMDB_BASE_URL   = "tmdb.base-url";
    public static final String LLM_PROVIDER    = "llm.provider";   // "openai" | "anthropic"
    public static final String LLM_API_KEY     = "llm.api-key";
    public static final String LLM_BASE_URL    = "llm.base-url";
    public static final String LLM_MODEL       = "llm.model";

    private final AppConfigRepository repository;
    private final MediaProperties properties;

    @PostConstruct
    void seed() {
        setIfAbsent(SOURCE_FOLDER, properties.getSourceFolder());
        setIfAbsent(TARGET_FOLDER, properties.getTargetFolder());
        setIfAbsent(TMDB_API_KEY,  properties.getTmdb().getApiKey());
        setIfAbsent(TMDB_BASE_URL, properties.getTmdb().getBaseUrl());
        setIfAbsent(LLM_PROVIDER,  "openai");
        setIfAbsent(LLM_API_KEY,   openAiApiKey);
        setIfAbsent(LLM_BASE_URL,  openAiBaseUrl);
        setIfAbsent(LLM_MODEL,     openAiModel);
    }

    public String get(String key) {
        return repository.findById(key).map(AppConfig::getValue).orElse(null);
    }

    public String getOrDefault(String key, String defaultValue) {
        String value = get(key);
        return value != null ? value : defaultValue;
    }

    public void set(String key, String value) {
        repository.save(new AppConfig(key, value));
    }

    public Map<String, String> getAll() {
        return repository.findAll().stream()
                .collect(Collectors.toMap(AppConfig::getConfigKey, AppConfig::getValue));
    }

    private static final java.util.Set<String> PLACEHOLDERS = java.util.Set.of(
            "YOUR_TMDB_BEARER_TOKEN", "ollama", "sk-ant-..."
    );

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

package com.npc.mediahandler.rest;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.npc.mediahandler.config.AppConfigService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/config")
@RequiredArgsConstructor
public class ConfigController {

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            AppConfigService.TMDB_API_KEY,
            AppConfigService.LLM_API_KEY
    );

    private final AppConfigService configService;

    @GetMapping
    public Map<String, String> getConfig() {
        return configService.getAll().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> SENSITIVE_KEYS.contains(e.getKey()) ? mask(e.getValue()) : e.getValue()
                ));
    }

    @PostMapping
    public void updateConfig(@RequestBody Map<String, String> updates) {
        updates.forEach((key, value) -> {
            if (value != null) {
                configService.set(key, value);
            }
        });
    }

    private String mask(String value) {
        if (value == null || value.length() <= 4) return "****";
        return "*".repeat(value.length() - 4) + value.substring(value.length() - 4);
    }
}

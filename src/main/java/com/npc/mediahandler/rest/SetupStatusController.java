package com.npc.mediahandler.rest;

import java.util.Map;
import java.util.Set;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.npc.mediahandler.config.AppConfigService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class SetupStatusController {

    private static final Set<String> PLACEHOLDERS = Set.of(
            "YOUR_TMDB_BEARER_TOKEN", "ollama", "sk-ant-..."
    );

    private final AppConfigService configService;

    @GetMapping("/api/setup-status")
    public Map<String, Boolean> status() {
        boolean needsSetup =
                isBlankOrPlaceholder(configService.get(AppConfigService.SOURCE_FOLDER)) ||
                isBlankOrPlaceholder(configService.get(AppConfigService.TARGET_FOLDER_MOVIES)) ||
                isBlankOrPlaceholder(configService.get(AppConfigService.TARGET_FOLDER_SHOWS)) ||
                isBlankOrPlaceholder(configService.get(AppConfigService.TMDB_API_KEY));
        return Map.of("needsSetup", needsSetup);
    }

    private static boolean isBlankOrPlaceholder(String v) {
        return v == null || v.isBlank() || PLACEHOLDERS.contains(v);
    }
}

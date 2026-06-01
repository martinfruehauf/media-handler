package com.npc.mediahandler.rest;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.npc.mediahandler.config.AppConfigService;
import com.npc.mediahandler.llm.WolService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/health")
@RequiredArgsConstructor
public class HealthController {

    private final AppConfigService configService;
    private final WolService wolService;

    @GetMapping
    public Map<String, Object> health() {
        return Map.of("tmdb", checkTmdb(), "llm", checkLlm());
    }

    private Map<String, Object> checkTmdb() {
        String apiKey = configService.get(AppConfigService.TMDB_API_KEY);
        if (apiKey == null || apiKey.isBlank() || apiKey.equals("YOUR_TMDB_BEARER_TOKEN")) {
            return Map.of("ok", false, "message", "API key not configured");
        }
        String baseUrl = configService.getOrDefault(AppConfigService.TMDB_BASE_URL, "https://api.themoviedb.org/3");
        try {
            RestClient.builder()
                    .baseUrl(baseUrl)
                    .defaultHeader("Authorization", "Bearer " + apiKey)
                    .build()
                    .get().uri("/configuration")
                    .retrieve()
                    .toBodilessEntity();
            return Map.of("ok", true, "message", "API key valid");
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                return Map.of("ok", false, "message", "Invalid API key");
            }
            return Map.of("ok", false, "message", "HTTP " + e.getStatusCode().value());
        } catch (Exception e) {
            return Map.of("ok", false, "message", "Unreachable");
        }
    }

    private Map<String, Object> checkLlm() {
        WolService.WolState wolState = wolService.getState();

        if (wolState == WolService.WolState.WAKING) {
            return Map.of("ok", false, "state", "warn", "message", wolService.getStatusMessage(), "pollFast", true);
        }

        String provider = configService.getOrDefault(AppConfigService.LLM_PROVIDER, "openai");
        String baseUrl = "anthropic".equals(provider)
                ? "https://api.anthropic.com"
                : configService.getLlmBaseUrl();
        try {
            URL url = new URL(baseUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.getResponseCode();
            conn.disconnect();
            if (wolState == WolService.WolState.FAILED) {
                wolService.resetToIdle();  // auto-recover: machine manually turned on
                return Map.of("ok", true, "state", "ok", "message", "Reachable");
            }
            if (wolState == WolService.WolState.AWAKE) {
                return Map.of("ok", true, "state", "warn", "message", wolService.getStatusMessage());
            }
            return Map.of("ok", true, "state", "ok", "message", "Reachable");
        } catch (Exception e) {
            if (wolState == WolService.WolState.FAILED) {
                return Map.of("ok", false, "state", "err", "message", wolService.getStatusMessage());
            }
            return Map.of("ok", false, "state", "err", "message", "Unreachable");
        }
    }
}

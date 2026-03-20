package com.npc.mediahandler.media;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class LlmResponseParser {

    public MediaMetadata parse(String response) {
        Map<String, String> fields = new HashMap<>();

        for (String line : response.strip().lines().toList()) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = line.substring(0, colon).strip().toLowerCase();
            String value = line.substring(colon + 1).strip();
            fields.put(key, value);
        }

        if (fields.containsKey("error")) {
            return new MediaMetadata(null, null, null, null, null, fields.get("error"));
        }

        return new MediaMetadata(
                fields.get("type"),
                fields.get("name"),
                fields.getOrDefault("year", ""),
                fields.getOrDefault("season", null),
                fields.getOrDefault("episode", null),
                null
        );
    }
}

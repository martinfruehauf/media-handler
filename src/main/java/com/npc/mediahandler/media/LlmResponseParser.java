package com.npc.mediahandler.media;

import java.util.HashMap;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

@Component
public class LlmResponseParser {

    public MediaMetadata parse(String response) {
        Map<String, String> fields = new HashMap<>();

        for (String line : StringUtils.strip(response).lines().toList()) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String key = StringUtils.strip(line.substring(0, colon)).toLowerCase();
            String value = StringUtils.strip(line.substring(colon + 1));
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

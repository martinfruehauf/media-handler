package com.npc.mediahandler.wiki;

import java.util.Optional;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.npc.mediahandler.config.AppConfigService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class WikipediaTitleService {

    private final AppConfigService configService;

    private static final String DE_WIKI_BASE = "https://de.wikipedia.org";

    /**
     * Returns the English Wikipedia article title for the given German title,
     * or empty if the lookup is disabled, nothing was found, or an error occurred.
     */
    public Optional<String> findEnglishTitle(String germanTitle) {
        if (!isEnabled()) return Optional.empty();
        try {
            String deTitle = searchDe(germanTitle);
            if (deTitle == null) return Optional.empty();

            String enTitle = getEnLink(deTitle);
            if (enTitle != null) {
                log.info("Wikipedia: '{}' → '{}'", germanTitle, enTitle);
            }
            return Optional.ofNullable(enTitle);
        } catch (Exception e) {
            log.warn("Wikipedia lookup failed for '{}': {}", germanTitle, e.getMessage());
            return Optional.empty();
        }
    }

    public boolean isEnabled() {
        return Boolean.parseBoolean(
                configService.getOrDefault(AppConfigService.WIKI_TITLE_LOOKUP, "false"));
    }

    private String searchDe(String query) {
        WikiSearchResponse response = restClient().get()
                .uri(b -> b.path("/w/api.php")
                        .queryParam("action", "query")
                        .queryParam("list", "search")
                        .queryParam("srsearch", query)
                        .queryParam("srlimit", "1")
                        .queryParam("format", "json")
                        .build())
                .retrieve()
                .body(WikiSearchResponse.class);

        if (response == null || response.query() == null
                || CollectionUtils.isEmpty(response.query().search())) {
            return null;
        }
        return response.query().search().get(0).title();
    }

    private String getEnLink(String dePageTitle) {
        WikiLangLinksResponse response = restClient().get()
                .uri(b -> b.path("/w/api.php")
                        .queryParam("action", "query")
                        .queryParam("prop", "langlinks")
                        .queryParam("titles", dePageTitle)
                        .queryParam("lllang", "en")
                        .queryParam("format", "json")
                        .build())
                .retrieve()
                .body(WikiLangLinksResponse.class);

        if (response == null || response.query() == null
                || MapUtils.isEmpty(response.query().pages())) {
            return null;
        }

        return response.query().pages().values().stream()
                .filter(p -> !CollectionUtils.isEmpty(p.langlinks()))
                .flatMap(p -> p.langlinks().stream())
                .filter(l -> "en".equals(l.lang()))
                .map(WikiLangLinksResponse.LangLink::title)
                .findFirst()
                .orElse(null);
    }

    private RestClient restClient() {
        return RestClient.builder()
                .baseUrl(DE_WIKI_BASE)
                .build();
    }
}

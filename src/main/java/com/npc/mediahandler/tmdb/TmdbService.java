package com.npc.mediahandler.tmdb;

import java.util.List;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.npc.mediahandler.config.AppConfigService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.npc.mediahandler.config.AppConfigService.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TmdbService {

    private final AppConfigService configService;

    public TmdbResult searchMovie(String name, String year) {
        log.info("→ TMDB movie search: name='{}', year='{}'", name, year);
        TmdbSearchResponse response = restClient().get()
                .uri(b -> b.path("/search/movie")
                        .queryParam("query", name)
                        .queryParam("year", year)
                        .queryParam("language", "en-US")
                        .build())
                .retrieve()
                .body(TmdbSearchResponse.class);

        if (response == null || CollectionUtils.isEmpty(response.results())) {
            log.warn("← TMDB no results for movie: name='{}', year='{}'", name, year);
            return null;
        }

        TmdbSearchResponse.MovieResult result = response.results().get(0);
        String resultYear = StringUtils.length(result.releaseDate()) >= 4
                ? result.releaseDate().substring(0, 4)
                : year;
        TmdbResult r = new TmdbResult(result.title(), resultYear, String.valueOf(result.id()));
        log.info("← TMDB movie found: '{}' ({})", r.name(), r.year());
        return r;
    }

    public TmdbResult searchShow(String name, String year) {
        log.info("→ TMDB show search: name='{}', year='{}'", name, year);
        TmdbShowSearchResponse response = restClient().get()
                .uri(b -> b.path("/search/tv")
                        .queryParam("query", name)
                        .queryParam("first_air_date_year", year)
                        .queryParam("language", "en-US")
                        .build())
                .retrieve()
                .body(TmdbShowSearchResponse.class);

        if (response == null || CollectionUtils.isEmpty(response.results())) {
            log.warn("← TMDB no results for show: name='{}', year='{}'", name, year);
            return null;
        }

        TmdbShowSearchResponse.ShowResult result = response.results().get(0);
        String resultYear = StringUtils.length(result.firstAirDate()) >= 4
                ? result.firstAirDate().substring(0, 4)
                : year;
        TmdbResult r = new TmdbResult(result.name(), resultYear, String.valueOf(result.id()));
        log.info("← TMDB show found: '{}' ({})", r.name(), r.year());
        return r;
    }

    private RestClient restClient() {
        String baseUrl = configService.getOrDefault(TMDB_BASE_URL, "https://api.themoviedb.org/3");
        String apiKey  = configService.getOrDefault(TMDB_API_KEY, "");
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    record TmdbSearchResponse(List<MovieResult> results) {
        record MovieResult(
                long id,
                String title,
                @JsonProperty("release_date") String releaseDate
        ) {}
    }

    record TmdbShowSearchResponse(List<ShowResult> results) {
        record ShowResult(
                long id,
                String name,
                @JsonProperty("first_air_date") String firstAirDate
        ) {}
    }
}

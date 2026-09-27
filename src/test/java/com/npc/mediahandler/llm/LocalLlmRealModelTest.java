package com.npc.mediahandler.llm;

import static com.npc.mediahandler.config.AppConfigService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.npc.mediahandler.config.AppConfig;
import com.npc.mediahandler.config.AppConfigRepository;
import com.npc.mediahandler.config.AppConfigService;
import com.npc.mediahandler.config.MediaProperties;
import com.npc.mediahandler.media.LlmResponseParser;
import com.npc.mediahandler.media.MediaMetadata;

/**
 * Runs filenames through a real llama-server + GGUF model. Skipped unless both are given:
 * <pre>./mvnw test -Dtest=LocalLlmRealModelTest -Dllama.server=/opt/llama.cpp/llama-server -Dllama.model=/path/model.gguf</pre>
 */
@EnabledIfSystemProperty(named = "llama.model", matches = ".+")
@EnabledIfSystemProperty(named = "llama.server", matches = ".+")
class LocalLlmRealModelTest {

    private static LocalLlmServerManager manager;
    private static FilenameParserService parser;

    @BeforeAll
    static void startServer() {
        Map<String, String> config = new HashMap<>(Map.of(
                LLM_MODE, LLM_MODE_LOCAL,
                LLM_LOCAL_SERVER_BINARY, System.getProperty("llama.server"),
                LLM_LOCAL_MODEL_PATH, System.getProperty("llama.model"),
                LLM_LOCAL_MODEL_NAME, "test-model",
                LLM_LOCAL_THREADS, System.getProperty("llama.threads", "4"),
                LLM_LOCAL_PORT, System.getProperty("llama.port", "18181"),
                LLM_LOCAL_IDLE_TIMEOUT, "0"));
        AppConfigRepository repository = mock(AppConfigRepository.class);
        when(repository.findById(anyString())).thenAnswer(inv ->
                Optional.ofNullable(config.get(inv.<String>getArgument(0)))
                        .map(v -> new AppConfig(inv.getArgument(0), v)));
        AppConfigService configService = new AppConfigService(repository, new MediaProperties());
        manager = new LocalLlmServerManager(configService);
        parser = new FilenameParserService(new DynamicChatClientProvider(configService), new LlmResponseParser(),
                mock(WolService.class), manager, configService);
    }

    @AfterAll
    static void stopServer() {
        manager.shutdown();
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', nullValues = "-", value = {
            "Star.Wars.Episode.III.Die.Rache.der.Sith.2005.German.EAC3.DL.2160p.UHD.BluRay.HDR.x265.REMUX-JJ.mkv | movie | Star Wars Episode III Die Rache der Sith | 2005 | -   | -",
            "Breaking.Bad.S03E07.German.BluRay.x264.mkv                                                        | show  | Breaking Bad                            |      | S03 | E07",
            "The.Mandalorian.2019.S01E04.1080p.WEB-DL.DDP5.1.x264.mkv                                          | show  | The Mandalorian                         | 2019 | S01 | E04",
            "Futurama.S10E01.GERMAN.DL.1080p.WEB.h264-WvF.mkv                                                  | show  | Futurama                                |      | S10 | E01",
            "Der.Schuh.des.Manitu.2001.German.1080p.BluRay.x264-DETAiLS.mkv                                    | movie | Der Schuh des Manitu                    | 2001 | -   | -",
            "Babylon.Berlin.S04E12.GERMAN.1080p.WEB.x264-WAYNE.mkv                                             | show  | Babylon Berlin                          |      | S04 | E12",
            "jajunge-south.park.s23e02.1080p.mkv                                                               | show  | South Park                              |      | S23 | E02",
    })
    void parsesFilename(String filename, String type, String name, String year, String season, String episode) {
        MediaMetadata m = parser.parse(filename);

        assertThat(m.error()).isNull();
        assertThat(m.type()).isEqualTo(type);
        assertThat(m.name()).isEqualTo(name);
        assertThat(m.year()).isEqualTo(year == null ? "" : year);
        assertThat(m.season()).isEqualTo(season);
        assertThat(m.episode()).isEqualTo(episode);
    }
}

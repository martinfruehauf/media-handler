package com.npc.mediahandler.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.npc.mediahandler.config.AppConfigService;
import com.npc.mediahandler.config.MediaProperties;
import com.npc.mediahandler.media.MediaMetadata;
import com.npc.mediahandler.tmdb.TmdbResult;

class FileRenameServiceTest {

    @TempDir Path dir;

    private final AppConfigService config = mock(AppConfigService.class);
    private final FileRenameService service = new FileRenameService(config, new MediaProperties());
    private Path movies;
    private Path shows;

    @BeforeEach
    void setUp() throws IOException {
        movies = Files.createDirectories(dir.resolve("movies"));
        shows = Files.createDirectories(dir.resolve("shows"));
        when(config.getOrDefault(eq(AppConfigService.TARGET_FOLDER_MOVIES), any())).thenReturn(movies.toString());
        when(config.getOrDefault(eq(AppConfigService.TARGET_FOLDER_SHOWS), any())).thenReturn(shows.toString());
        when(config.getOrDefault(eq(AppConfigService.FILE_OVERWRITE), any())).thenReturn("false");
        when(config.getOrDefault(eq(AppConfigService.FILE_COPY_MODE), any())).thenReturn("false");
    }

    private Path source(String name) throws IOException {
        return Files.writeString(Files.createDirectories(dir.resolve("src")).resolve(name), "x");
    }

    private static MediaMetadata movie() {
        return new MediaMetadata("movie", "whatever", "", null, null, null);
    }

    @Test
    void movieGetsItsOwnFolderByDefault() throws IOException {
        when(config.getOrDefault(eq(AppConfigService.TARGET_MOVIES_OWN_FOLDER), any())).thenReturn("true");

        Path target = service.process(source("a.mkv"), movie(), new TmdbResult("Alien", "1979", "348", "Alien", "en")).orElseThrow();

        assertThat(target).isEqualTo(movies.resolve("Alien (1979)").resolve("Alien (1979).mkv"));
        assertThat(target).exists();
    }

    @Test
    void movieDirectlyInTargetWhenDisabled() throws IOException {
        when(config.getOrDefault(eq(AppConfigService.TARGET_MOVIES_OWN_FOLDER), any())).thenReturn("false");

        Path target = service.process(source("a.mkv"), movie(), new TmdbResult("Alien", "1979", "348", "Alien", "en")).orElseThrow();

        assertThat(target).isEqualTo(movies.resolve("Alien (1979).mkv"));
    }

    @Test
    void slashInTitleDoesNotCreateSubfolder() throws IOException {
        when(config.getOrDefault(eq(AppConfigService.TARGET_MOVIES_OWN_FOLDER), any())).thenReturn("true");

        Path target = service.process(source("f.mkv"), movie(), new TmdbResult("Face/Off", "1997", "754", "Face/Off", "en")).orElseThrow();

        assertThat(target).isEqualTo(movies.resolve("Face-Off (1997)").resolve("Face-Off (1997).mkv"));
    }

    @Test
    void episodeLayoutUnchanged() throws IOException {
        Path target = service.process(source("e.mkv"), new MediaMetadata("show", "x", "", "S23", "E02", null),
                new TmdbResult("South Park", "1997", "2190", "South Park", "en")).orElseThrow();

        assertThat(target).isEqualTo(shows.resolve("South Park (1997)/Season 23/South Park (1997) - S23E02.mkv"));
    }
}

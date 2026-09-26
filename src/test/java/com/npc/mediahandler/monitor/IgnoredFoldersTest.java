package com.npc.mediahandler.monitor;

import static org.assertj.core.api.Assertions.assertThat;
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

class IgnoredFoldersTest {

    @TempDir Path source;

    private final AppConfigService configService = mock(AppConfigService.class);
    private final IgnoredFolders ignoredFolders = new IgnoredFolders(configService, new MediaProperties());

    @BeforeEach
    void setUp() throws IOException {
        when(configService.getOrDefault(AppConfigService.SOURCE_FOLDER, null)).thenReturn(source.toString());
        for (String f : new String[] {
                "Movie.2005.mkv", "Show/Show.S01E01.mkv",
                "usenet/incomplete/Some.Movie.mkv", "usenet/Other.mkv",
                "usenet2/Keep.mkv", "downloads/incomplete/Partial.mkv", "downloads/Done.mkv" }) {
            Path p = source.resolve(f);
            Files.createDirectories(p.getParent());
            Files.writeString(p, "x");
        }
    }

    private void configure(String value) {
        when(configService.getOrDefault(AppConfigService.SOURCE_IGNORED_FOLDERS, "")).thenReturn(value);
    }

    @Test
    void walkSkipsIgnoredFoldersEntirely() throws IOException {
        configure("usenet, downloads/incomplete");

        assertThat(ignoredFolders.walkFiles(source)).map(p -> source.relativize(p).toString())
                .containsExactlyInAnyOrder("Movie.2005.mkv", "Show/Show.S01E01.mkv", "usenet2/Keep.mkv",
                        "downloads/Done.mkv");
    }

    @Test
    void matchesWholePathComponentsOnly() {
        configure("usenet");

        assertThat(ignoredFolders.isIgnored(source.resolve("usenet"))).isTrue();
        assertThat(ignoredFolders.isIgnored(source.resolve("usenet/incomplete/Some.Movie.mkv"))).isTrue();
        assertThat(ignoredFolders.isIgnored(source.resolve("usenet/../Movie.2005.mkv"))).isFalse();
        assertThat(ignoredFolders.isIgnored(source.resolve("usenet2/Keep.mkv"))).isFalse();
        assertThat(ignoredFolders.isIgnored(source.resolve("Movie.2005.mkv"))).isFalse();
    }

    @Test
    void acceptsAbsolutePathsNewlinesAndBlankEntries() {
        configure(source.resolve("downloads") + "\n , ");

        assertThat(ignoredFolders.isIgnored(source.resolve("downloads/Done.mkv"))).isTrue();
        assertThat(ignoredFolders.isIgnored(source.resolve("usenet/Other.mkv"))).isFalse();
    }

    @Test
    void emptySettingIgnoresNothing() throws IOException {
        configure("");

        assertThat(ignoredFolders.walkFiles(source)).hasSize(7);
    }
}

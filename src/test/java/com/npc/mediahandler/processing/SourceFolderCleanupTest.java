package com.npc.mediahandler.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.npc.mediahandler.config.AppConfigService;
import com.npc.mediahandler.config.MediaProperties;
import com.npc.mediahandler.monitor.IgnoredFolders;
import com.npc.mediahandler.monitor.SampleFiles;

class SourceFolderCleanupTest {

    private static final long MB = 1024 * 1024;

    @TempDir Path source;

    private final AppConfigService configService = mock(AppConfigService.class);
    private final MediaProperties properties = new MediaProperties();
    private final List<ProcessingNote> notes = new ArrayList<>();
    private SourceFolderCleanup cleanup;

    @BeforeEach
    void setUp() {
        when(configService.getOrDefault(eq(AppConfigService.FOLDER_CLEANUP_ENABLED), any())).thenReturn("true");
        when(configService.getOrDefault(eq(AppConfigService.SOURCE_FOLDER), any())).thenReturn(source.toString());
        when(configService.getOrDefault(AppConfigService.SOURCE_IGNORED_FOLDERS, "")).thenReturn("usenet");
        when(configService.getInt(eq(AppConfigService.FOLDER_CLEANUP_SMALL_VIDEO_MAX_MB), anyInt())).thenReturn(200);
        when(configService.getInt(eq(AppConfigService.SOURCE_SAMPLE_MAX_MB), anyInt())).thenReturn(200);
        cleanup = new SourceFolderCleanup(configService, properties,
                new IgnoredFolders(configService, properties), new SampleFiles(configService));
    }

    /** Creates a sparse file of the given size (no disk space used). */
    private Path file(String relative, long size) throws IOException {
        Path p = source.resolve(relative);
        Files.createDirectories(p.getParent());
        try (RandomAccessFile f = new RandomAccessFile(p.toFile(), "rw")) {
            f.setLength(size);
        }
        return p;
    }

    @Test
    void removesSampleSubfolderMetaFilesAndEmptyWrapperFolders() throws IOException {
        Path moved = source.resolve("Movie.2024 - by uploader/Movie.2024.2160p/Movie.2024.2160p.mkv");
        file("Movie.2024 - by uploader/Movie.2024.2160p/Sample/movie.2024.2160p.sample.mkv", 150 * MB);
        file("Movie.2024 - by uploader/Movie.2024.2160p/Proof/cover.jpg", 1000);
        file("Movie.2024 - by uploader/Movie.2024.2160p/movie.nfo", 100);

        cleanup.cleanup(moved, notes);

        assertThat(source.resolve("Movie.2024 - by uploader")).doesNotExist();
        assertThat(source).exists();
    }

    @Test
    void keepsLargeVideosAndSmallEpisodes() throws IOException {
        Path moved = source.resolve("Show.S01/Show.S01E01.mkv");
        Path nextEpisode = file("Show.S01/Show.S01E02.mkv", 1500 * MB);
        Path smallEpisode = file("Show.S01/Show.S01E03.mkv", 150 * MB);
        file("Show.S01/Extras/featurette.mkv", 120 * MB);

        cleanup.cleanup(moved, notes);

        assertThat(nextEpisode).exists();
        assertThat(smallEpisode).exists();
        assertThat(source.resolve("Show.S01/Extras")).doesNotExist();
    }

    @Test
    void doesNothingWhileArchivePresent() throws IOException {
        Path moved = source.resolve("Movie/Movie.mkv");
        Path nfo = file("Movie/movie.nfo", 100);
        file("Movie/Sample/movie.part01.rar", 100);

        cleanup.cleanup(moved, notes);

        assertThat(nfo).exists();
    }

    @Test
    void neverTouchesSourceRootOrIgnoredFolders() throws IOException {
        Path rootNfo = file("readme.nfo", 100);
        Path ignoredNfo = file("usenet/some.nfo", 100);

        cleanup.cleanup(source.resolve("Movie.mkv"), notes);
        cleanup.cleanup(source.resolve("usenet/Movie.mkv"), notes);

        assertThat(rootNfo).exists();
        assertThat(ignoredNfo).exists();
    }

    @Test
    void detectsEpisodeMarkers() {
        assertThat(SourceFolderCleanup.isEpisode(Path.of("Show.S01E03.720p.mkv"))).isTrue();
        assertThat(SourceFolderCleanup.isEpisode(Path.of("show.1x03.mkv"))).isTrue();
        assertThat(SourceFolderCleanup.isEpisode(Path.of("Movie.2024.1920x1080.x264.mkv"))).isFalse();
        assertThat(SourceFolderCleanup.isEpisode(Path.of("featurette.mkv"))).isFalse();
    }
}

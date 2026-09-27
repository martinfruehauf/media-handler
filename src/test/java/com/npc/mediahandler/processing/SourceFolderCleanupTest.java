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
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;
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
    private final MediaFileRepository repository = mock(MediaFileRepository.class);
    private final ProcessingQueue queue = new ProcessingQueue();
    private final List<ProcessingNote> notes = new ArrayList<>();
    private SourceFolderCleanup cleanup;

    @BeforeEach
    void setUp() {
        when(configService.getOrDefault(eq(AppConfigService.FOLDER_CLEANUP_ENABLED), any())).thenReturn("true");
        when(configService.getOrDefault(eq(AppConfigService.SOURCE_FOLDER), any())).thenReturn(source.toString());
        when(configService.getOrDefault(AppConfigService.SOURCE_IGNORED_FOLDERS, "")).thenReturn("usenet");
        when(configService.getInt(eq(AppConfigService.FOLDER_CLEANUP_SMALL_VIDEO_MAX_MB), anyInt())).thenReturn(200);
        when(configService.getInt(eq(AppConfigService.SOURCE_SAMPLE_MAX_MB), anyInt())).thenReturn(200);
        when(configService.getInt(eq(AppConfigService.FOLDER_CLEANUP_STALE_HOURS), anyInt())).thenReturn(6);
        cleanup = new SourceFolderCleanup(configService, properties,
                new IgnoredFolders(configService, properties), new SampleFiles(configService),
                repository, queue, new ProcessingGateService());
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
    void keepsPartialDownloadsAndFilesWithOpenRecords() throws IOException {
        Path partial = file("Movie/other.mkv.part", 100);
        cleanup.cleanup(source.resolve("Movie/Movie.mkv"), notes);
        assertThat(partial).exists();

        Path failed = file("Film/Unbekannter.Film.mkv", 150 * MB);
        when(repository.findTopBySourcePathOrderByIdDesc(failed.toString()))
                .thenReturn(Optional.of(record(failed, MediaFileStatus.TMDB_FAILED)));
        cleanup.cleanup(source.resolve("Film/Film.mkv"), notes);
        assertThat(failed).exists();
    }

    @Test
    void sweepCleansStaleFoldersMediaHandlerKnows() throws IOException {
        Path known = file("Old.Release/Old.Release/Proof/proof.jpg", 1000);
        Path unknown = file("Never.Seen/readme.nfo", 100);
        Path fresh = file("Fresh.Release/movie.nfo", 100);
        ageTree(source.resolve("Old.Release"), 7);
        ageTree(source.resolve("Never.Seen"), 7);
        when(repository.existsBySourcePathStartingWith(source.resolve("Old.Release") + "/")).thenReturn(true);
        when(repository.existsBySourcePathStartingWith(source.resolve("Fresh.Release") + "/")).thenReturn(true);

        cleanup.sweepStaleFolders();

        assertThat(source.resolve("Old.Release")).doesNotExist();
        assertThat(unknown).exists();   // no record: MediaHandler never saw it
        assertThat(fresh).exists();     // changed less than 6 h ago
        assertThat(known).doesNotExist();
    }

    @Test
    void sweepLeavesFoldersWithUnprocessedVideosUntouched() throws IOException {
        Path nfo = file("Show.S01/Show.S01E01/episode.nfo", 100);
        Path episode = file("Show.S01/Show.S01E02/Show.S01E02.mkv", 700 * MB);
        ageTree(source.resolve("Show.S01"), 7);
        when(repository.existsBySourcePathStartingWith(source.resolve("Show.S01") + "/")).thenReturn(true);

        cleanup.sweepStaleFolders();

        assertThat(episode).exists();
        assertThat(nfo).exists();
    }

    private static MediaFileRecord record(Path file, MediaFileStatus status) {
        return MediaFileRecord.builder().sourcePath(file.toString()).status(status).build();
    }

    private static void ageTree(Path root, int hours) throws IOException {
        FileTime old = FileTime.from(Instant.now().minus(Duration.ofHours(hours)));
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path p : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.setLastModifiedTime(p, old);
            }
        }
    }

    @Test
    void detectsEpisodeMarkers() {
        assertThat(SourceFolderCleanup.isEpisode(Path.of("Show.S01E03.720p.mkv"))).isTrue();
        assertThat(SourceFolderCleanup.isEpisode(Path.of("show.1x03.mkv"))).isTrue();
        assertThat(SourceFolderCleanup.isEpisode(Path.of("Movie.2024.1920x1080.x264.mkv"))).isFalse();
        assertThat(SourceFolderCleanup.isEpisode(Path.of("featurette.mkv"))).isFalse();
    }
}

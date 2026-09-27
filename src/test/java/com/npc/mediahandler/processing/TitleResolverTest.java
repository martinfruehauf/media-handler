package com.npc.mediahandler.processing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.npc.mediahandler.config.AppConfigService;
import com.npc.mediahandler.config.MediaProperties;
import com.npc.mediahandler.llm.FilenameParserService;
import com.npc.mediahandler.media.MediaMetadata;
import com.npc.mediahandler.tmdb.TmdbResult;
import com.npc.mediahandler.tmdb.TmdbService;
import com.npc.mediahandler.wiki.WikipediaTitleService;

class TitleResolverTest {

    private static final Path ROOT = Path.of("/mnt/nas/temp");
    private static final TmdbResult SOUTH_PARK = new TmdbResult("South Park", "1997", "2190", "South Park", "en");

    private final FilenameParserService llm = mock(FilenameParserService.class);
    private final TmdbService tmdb = mock(TmdbService.class);
    private final WikipediaTitleService wiki = mock(WikipediaTitleService.class);
    private final AppConfigService config = mock(AppConfigService.class);
    private final List<ProcessingNote> notes = new ArrayList<>();
    private TitleResolver resolver;

    @BeforeEach
    void setUp() {
        when(config.getOrDefault(eq(AppConfigService.SOURCE_FOLDER), any())).thenReturn(ROOT.toString());
        resolver = new TitleResolver(llm, tmdb, wiki, config, new MediaProperties());
    }

    private static MediaMetadata show(String name, String season, String episode) {
        return new MediaMetadata("show", name, "", season, episode, null);
    }

    @Test
    void groupPrefixFixedByTitleVariantWithoutMoreLlmCalls() {
        String file = "jajunge-south.park.s23e10.1080p.mkv";
        Path source = ROOT.resolve("South.Park.S23.German.DL.AC3D.1080p.BluRay.x264-JaJunge")
                .resolve("South.Park.S23E10.Weihnachtsschnee.German.DL.AC3D.1080p.BluRay.x264-JaJunge").resolve(file);
        when(llm.parse(file)).thenReturn(show("jajunge-south", "S23", "E10"));
        when(tmdb.searchShow("south park", "")).thenReturn(SOUTH_PARK);

        TitleResolver.Resolution r = resolver.resolve(file, source, notes);

        assertThat(r.isFound()).isTrue();
        assertThat(r.tmdb().name()).isEqualTo("South Park");
        assertThat(r.metadata().season()).isEqualTo("S23");
        assertThat(r.metadata().episode()).isEqualTo("E10");
        verify(llm, times(1)).parse(anyString());
        assertThat(notes).extracting(ProcessingNote::step).containsExactly("LLM", "TMDB", "VARIANTS");
        assertThat(notes.get(2).outcome()).isEqualTo(ProcessingNote.OK);
    }

    @Test
    void variantHitWithDifferentTitleIsRejected() {
        String file = "grp-some.show.s01e02.mkv";
        Path source = ROOT.resolve(file);
        when(llm.parse(file)).thenReturn(show("grp-some", "S01", "E02"));
        when(tmdb.searchShow(anyString(), any())).thenReturn(
                new TmdbResult("Something Else", "2001", "1", "Something Else", "en"));

        TitleResolver.Resolution r = resolver.resolve(file, source, notes);

        // "grp-some" (the LLM's name) is accepted as TMDB's top hit; variants would need an exact title
        assertThat(r.isFound()).isTrue();
        assertThat(notes).extracting(ProcessingNote::step).containsExactly("LLM", "TMDB");

        notes.clear();
        when(tmdb.searchShow("grp-some", "")).thenReturn(null);
        r = resolver.resolve(file, source, notes);
        assertThat(r.isFound()).isFalse();
        assertThat(r.failStatus()).isEqualTo(MediaFileStatus.TMDB_FAILED);
    }

    @Test
    void incompleteFilenameParseFallsBackToFolder() {
        String file = "xmshg13.mkv";
        Path source = ROOT.resolve("The.Mandalorian.2019.S01E04").resolve(file);
        when(llm.parse(file)).thenReturn(new MediaMetadata("movie", "", "", null, null, null));
        when(llm.parse("The.Mandalorian.2019.S01E04")).thenReturn(new MediaMetadata("show", "The Mandalorian", "2019", "S01", "E04", null));
        TmdbResult mando = new TmdbResult("The Mandalorian", "2019", "82856", "The Mandalorian", "en");
        when(tmdb.searchShow("the mandalorian", "")).thenReturn(mando);

        TitleResolver.Resolution r = resolver.resolve(file, source, notes);

        assertThat(r.isFound()).isTrue();
        assertThat(r.metadata().season()).isEqualTo("S01");
        // the folder's title words already match exactly, before the folder is sent to the LLM
        verify(llm, never()).parse("The.Mandalorian.2019.S01E04");
    }

    @Test
    void genericFoldersAreSkippedAndSeasonComesFromFilename() {
        String file = "abc123.s02e05.mkv";
        Path source = ROOT.resolve("Some Show Complete").resolve("Subs").resolve(file);
        when(llm.parse(file)).thenReturn(show("abc123", "S02", "E05"));
        when(llm.parse("Some Show Complete")).thenReturn(show("Some Show", null, null));
        TmdbResult someShow = new TmdbResult("Some Show", "2010", "7", "Some Show", "en");
        when(tmdb.searchShow(argThat(s -> s.equalsIgnoreCase("some show")), any())).thenReturn(someShow);

        TitleResolver.Resolution r = resolver.resolve(file, source, notes);

        assertThat(r.isFound()).isTrue();
        assertThat(r.metadata().season()).isEqualTo("S02");
        assertThat(r.metadata().episode()).isEqualTo("E05");
        verify(llm, never()).parse("Subs");
    }

    @Test
    void unreachableLlmStopsImmediately() {
        String file = "Movie.2005.mkv";
        Path source = ROOT.resolve("Movie.2005").resolve(file);
        when(llm.parse(file)).thenReturn(new MediaMetadata(null, null, null, null, null,
                "LLM request failed: No route to host"));

        TitleResolver.Resolution r = resolver.resolve(file, source, notes);

        assertThat(r.failStatus()).isEqualTo(MediaFileStatus.LLM_FAILED);
        verify(llm, times(1)).parse(anyString());
        verify(tmdb, never()).searchMovie(anyString(), any());
    }
}

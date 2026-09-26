package com.npc.mediahandler.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.npc.mediahandler.media.MediaMetadata;

class GroundInInputTest {

    private static MediaMetadata show(String year, String season, String episode) {
        return new MediaMetadata("show", "Name", year, season, episode, null);
    }

    @Test
    void keepsValuesPresentInTheFilename() {
        MediaMetadata m = FilenameParserService.groundInInput(
                show("2019", "S01", "E04"), "The.Mandalorian.2019.S01E04.1080p.mkv");
        assertThat(m).isEqualTo(show("2019", "S01", "E04"));
    }

    @Test
    void acceptsAlternativeEpisodeMarkers() {
        assertThat(FilenameParserService.groundInInput(show("", "S04", "E05"), "Stranger.Things.4x05.mkv").season())
                .isEqualTo("S04");
        assertThat(FilenameParserService.groundInInput(show("", "S3", "E7"), "show s03.e07 720p.mkv").season())
                .isEqualTo("S3");
        assertThat(FilenameParserService.groundInInput(show("", "S00", "E01"), "Doctor.Who.S00E01.mkv").season())
                .isEqualTo("S00");
    }

    @Test
    void dropsInventedYear() {
        MediaMetadata m = FilenameParserService.groundInInput(show("2010", "S10", "E01"), "Futurama.S10E01.GERMAN.mkv");
        assertThat(m.year()).isEmpty();
        assertThat(m.season()).isEqualTo("S10");
    }

    @Test
    void dropsSeasonAndEpisodeThatDoNotMatchTheFilename() {
        // model read 4x05 as S05E05
        MediaMetadata wrong = FilenameParserService.groundInInput(show("", "S05", "E05"), "Stranger.Things.4x05.mkv");
        assertThat(wrong.season()).isNull();
        assertThat(wrong.episode()).isNull();

        // no marker at all
        MediaMetadata invented = FilenameParserService.groundInInput(
                show("", "S01", "E01"), "Breaking.Bad.Complete.Series.German.1080p.mkv");
        assertThat(invented.season()).isNull();
        assertThat(invented.episode()).isNull();

        // S1E1 must not match inside S11E12
        assertThat(FilenameParserService.groundInInput(show("", "S1", "E1"), "Show.S11E12.mkv").season()).isNull();
    }

    @Test
    void leavesMoviesAndErrorsAlone() {
        MediaMetadata movie = new MediaMetadata("movie", "Die Hard", "1988", null, null, null);
        assertThat(FilenameParserService.groundInInput(movie, "Die.Hard.1988.mkv")).isEqualTo(movie);

        MediaMetadata error = new MediaMetadata(null, null, null, null, null, "not a media file");
        assertThat(FilenameParserService.groundInInput(error, "x.txt")).isEqualTo(error);
    }
}

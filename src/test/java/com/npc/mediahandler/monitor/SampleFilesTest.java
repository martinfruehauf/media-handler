package com.npc.mediahandler.monitor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SampleFilesTest {

    private static final long MB = 1024 * 1024;

    @Test
    void smallFileNamedSampleIsSample() {
        assertThat(SampleFiles.isSample(
                "better.man.2024.german.dl.2160p.uhd.bluray.x265-endstation.sample.mkv", 80 * MB, 200)).isTrue();
        assertThat(SampleFiles.isSample("der.mann.1983.1080p.bluray.x264-infotv-sample.mkv", 30 * MB, 200)).isTrue();
        assertThat(SampleFiles.isSample("SAMPLE.mkv", 200 * MB, 200)).isTrue();
    }

    @Test
    void largeFileNamedSampleIsNotSample() {
        assertThat(SampleFiles.isSample("The.Sample.Movie.2021.1080p.mkv", 4000 * MB, 200)).isFalse();
        assertThat(SampleFiles.isSample("x.sample.mkv", 200 * MB + 1, 200)).isFalse();
    }

    @Test
    void smallFileWithoutSampleInNameIsNotSample() {
        assertThat(SampleFiles.isSample("Short.Film.2020.mkv", 10 * MB, 200)).isFalse();
    }
}

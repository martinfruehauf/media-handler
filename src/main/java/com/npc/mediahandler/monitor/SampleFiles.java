package com.npc.mediahandler.monitor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.springframework.stereotype.Component;

import com.npc.mediahandler.config.AppConfigService;

import lombok.RequiredArgsConstructor;

/**
 * Release sample clips (e.g. {@code movie.2024.2160p-grp.sample.mkv}). A video file whose name contains
 * "sample" is skipped unless it is larger than {@code source.sample.max-mb} — a real movie can have
 * "sample" in its title, but it will never be that small.
 */
@Component
@RequiredArgsConstructor
public class SampleFiles {

    public static final int DEFAULT_MAX_MB = 200;

    private final AppConfigService configService;

    public long maxMb() {
        return Math.max(0, configService.getInt(AppConfigService.SOURCE_SAMPLE_MAX_MB, DEFAULT_MAX_MB));
    }

    public boolean isSample(Path file, long sizeBytes) {
        return isSample(file.getFileName().toString(), sizeBytes, maxMb());
    }

    /** Reads the size itself; an unreadable file is not treated as a sample. */
    public boolean isSample(Path file) {
        try {
            return isSample(file, Files.size(file));
        } catch (IOException e) {
            return false;
        }
    }

    static boolean isSample(String filename, long sizeBytes, long maxMb) {
        return filename.toLowerCase(Locale.ROOT).contains("sample")
                && sizeBytes <= maxMb * 1024 * 1024;
    }
}

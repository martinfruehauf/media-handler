package com.npc.mediahandler.llm;

import java.util.Objects;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import com.npc.mediahandler.config.AppConfigService;
import com.npc.mediahandler.media.LlmResponseParser;
import com.npc.mediahandler.media.MediaMetadata;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class FilenameParserService {

    /** Ensures only one request is in flight to the LLM at a time. */
    private final Semaphore llmSlot = new Semaphore(1);

    static final String SYSTEM_PROMPT = """
            Your sole purpose is to extract clean metadata from a messy movie or TV show filename.

            Rules:
            - Remove the file extension
            - Remove technical tags (resolution, codec, audio, source, release group, etc.)
            - Replace dots and underscores used as spaces with actual spaces
            - Only include the year if you are confident it is the release year (4-digit number between 1888 and current year)
            - Detect whether the file is a movie or a TV show episode (look for patterns like S01E03, 1x03, etc.)

            If the input contains "Folder: <folder> | File: <filename>", use both to extract metadata.
            The folder name usually contains the title (and possibly season/episode for shows).
            The filename may contain season/episode markers even when the title is garbled.
            Combine whatever is useful from both.

            For a MOVIE, respond in exactly this format:
            type: movie
            name: <clean title>
            year: <4-digit year or empty>

            For a TV SHOW, respond in exactly this format:
            type: show
            name: <clean series title>
            year: <4-digit year or empty>
            season: <e.g. S01>
            episode: <e.g. E03>

            If the input is not a recognizable filename, respond in exactly this format:
            error: <one sentence explaining what failed>

            Examples:
            Input:  Star.Wars.Episode.III.Die.Rache.der.Sith.2005.German.EAC3.DL.2160p.UHD.BluRay.HDR.x265.REMUX-JJ.mkv
            Output:
            type: movie
            name: Star Wars Episode III Die Rache der Sith
            year: 2005

            Input:  Breaking.Bad.S03E07.German.BluRay.x264.mkv
            Output:
            type: show
            name: Breaking Bad
            year:
            season: S03
            episode: E07

            Input:  The.Mandalorian.2019.S01E04.1080p.WEB-DL.DDP5.1.x264.mkv
            Output:
            type: show
            name: The Mandalorian
            year: 2019
            season: S01
            episode: E04

            Input:  Folder: The.Mandalorian.2019.S01E04 | File: xmshg13.mov
            Output:
            type: show
            name: The Mandalorian
            year: 2019
            season: S01
            episode: E04

            Input:  randomgarbage_xyz.txt
            Output:
            error: Input does not appear to be a movie or TV show filename.
            """;

    private static final String LOCAL_UNAVAILABLE = "Local LLM unavailable: ";

    private final DynamicChatClientProvider chatClientProvider;
    private final LlmResponseParser responseParser;
    private final WolService wolService;
    private final LocalLlmServerManager localServer;
    private final AppConfigService configService;

    public MediaMetadata parse(String filename) {
        try {
            log.debug("Waiting for LLM slot: {}", filename);
            llmSlot.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new MediaMetadata(null, null, null, null, null, "Interrupted while waiting for LLM");
        }
        boolean local = configService.isLocalLlm();
        try {
            if (local) {
                try {
                    localServer.beforeLlmRequest();
                } catch (IllegalStateException e) {
                    return new MediaMetadata(null, null, null, null, null, LOCAL_UNAVAILABLE + e.getMessage());
                }
            } else {
                wolService.beforeLlmRequest();
            }
            log.info("→ LLM request: '{}'", filename);
            String response = chatClientProvider.getChatClient().prompt()
                    .system(SYSTEM_PROMPT)
                    .user(filename)
                    .call()
                    .content();
            String preview = response != null
                    ? StringUtils.left(response.replaceAll("\\s+", " "), 200)
                    : "null";
            log.info("← LLM response for '{}': {}", filename, preview);
            MediaMetadata metadata = responseParser.parse(response);
            return local ? groundInInput(metadata, filename) : metadata;
        } finally {
            llmSlot.release();
            if (local) {
                localServer.afterLlmRequest();
            } else {
                wolService.afterLlmRequest();
            }
        }
    }

    public MediaMetadata parseWithFolderFallback(String filename, @Nullable String folderName) {
        // Attempt 1: filename alone
        MediaMetadata result = parse(filename);
        if (isComplete(result)) return result;
        // No point retrying with the folder name if the server itself could not start
        if (result.isError() && result.error().startsWith(LOCAL_UNAVAILABLE)) return result;

        if (StringUtils.isNotBlank(folderName)) {
            // Attempt 2: folder name alone
            result = parse(folderName);
            if (isComplete(result)) return result;

            // Attempt 3: combined — LLM sees both
            result = parse("Folder: " + folderName + " | File: " + filename);
            if (isComplete(result)) return result;
        }

        // If still a show with missing S/E, return explicit error
        if (!result.isError() && result.isShow()
                && (StringUtils.isBlank(result.season()) || StringUtils.isBlank(result.episode()))) {
            return new MediaMetadata(result.type(), result.name(), result.year(),
                result.season(), result.episode(),
                "TV show is missing season or episode — cannot rename without S/E");
        }
        return result;  // error or best effort movie
    }

    /**
     * Small local models tend to fill in a plausible year, season or episode that isn't in the
     * filename. Keep those values only if they literally appear in the input; otherwise blank them
     * so the folder-name fallback and the "missing season or episode" check handle the file
     * instead of a wrong rename.
     */
    static MediaMetadata groundInInput(MediaMetadata m, String input) {
        if (m.isError()) return m;
        String year = StringUtils.isNotBlank(m.year()) && input.contains(m.year().strip()) ? m.year() : "";
        String season  = m.season();
        String episode = m.episode();
        if (m.isShow() && !hasEpisodeMarker(input, season, episode)) {
            season  = null;
            episode = null;
        }
        if (!Objects.equals(year, m.year()) || !Objects.equals(season, m.season())) {
            log.info("Dropped values not found in '{}': year={}, season={}, episode={}",
                    input, m.year(), m.season(), m.episode());
        }
        return new MediaMetadata(m.type(), m.name(), year, season, episode, null);
    }

    /** True if the input contains S{season}E{episode} (e.g. S03E07, s3.e7) or {season}x{episode} (e.g. 3x07). */
    private static boolean hasEpisodeMarker(String input, String season, String episode) {
        Integer s = markerNumber(season, "S");
        Integer e = markerNumber(episode, "E");
        if (s == null || e == null) return false;
        Pattern marker = Pattern.compile(
                "(?i)(?<![a-z0-9])(s0*" + s + "[ ._-]*e0*" + e + "|0*" + s + "x0*" + e + ")(?!\\d)");
        return marker.matcher(input).find();
    }

    private static Integer markerNumber(String value, String prefix) {
        String digits = StringUtils.removeStartIgnoreCase(StringUtils.strip(value), prefix);
        return StringUtils.isNumeric(digits) && digits.length() <= 6 ? Integer.valueOf(digits) : null;
    }

    private boolean isComplete(MediaMetadata m) {
        if (m == null || m.isError()) return false;
        if (m.isMovie()) return StringUtils.isNotBlank(m.name());
        if (m.isShow()) return StringUtils.isNotBlank(m.name())
            && StringUtils.isNotBlank(m.season())
            && StringUtils.isNotBlank(m.episode());
        return false;
    }
}

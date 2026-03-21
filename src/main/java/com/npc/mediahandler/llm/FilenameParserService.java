package com.npc.mediahandler.llm;

import java.util.concurrent.Semaphore;

import org.apache.commons.lang3.StringUtils;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

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

    private final DynamicChatClientProvider chatClientProvider;
    private final LlmResponseParser responseParser;

    public MediaMetadata parse(String filename) {
        try {
            log.debug("Waiting for LLM slot: {}", filename);
            llmSlot.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new MediaMetadata(null, null, null, null, null, "Interrupted while waiting for LLM");
        }
        try {
            log.info("→ LLM request: '{}'", filename);
            String response = chatClientProvider.getChatClient().prompt()
                    .system(SYSTEM_PROMPT)
                    .user(filename)
                    .call()
                    .content();
            String preview = response != null
                    ? response.replaceAll("\\s+", " ").substring(0, Math.min(200, response.length()))
                    : "null";
            log.info("← LLM response for '{}': {}", filename, preview);
            return responseParser.parse(response);
        } finally {
            llmSlot.release();
        }
    }

    public MediaMetadata parseWithFolderFallback(String filename, @Nullable String folderName) {
        // Attempt 1: filename alone
        MediaMetadata result = parse(filename);
        if (isComplete(result)) return result;

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

    private boolean isComplete(MediaMetadata m) {
        if (m == null || m.isError()) return false;
        if (m.isMovie()) return StringUtils.isNotBlank(m.name());
        if (m.isShow()) return StringUtils.isNotBlank(m.name())
            && StringUtils.isNotBlank(m.season())
            && StringUtils.isNotBlank(m.episode());
        return false;
    }
}

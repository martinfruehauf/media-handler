package com.npc.mediahandler.rest;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import com.npc.mediahandler.media.LlmResponseParser;
import com.npc.mediahandler.media.MediaMetadata;
import com.npc.mediahandler.media.MovieHandler;
import com.npc.mediahandler.media.ShowHandler;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Controller
public class ChatStreamController {

    private static final String SYSTEM_PROMPT = """
            Your sole purpose is to extract clean metadata from a messy movie or TV show filename.

            Rules:
            - Remove the file extension
            - Remove technical tags (resolution, codec, audio, source, release group, etc.)
            - Replace dots and underscores used as spaces with actual spaces
            - Only include the year if you are confident it is the release year (4-digit number between 1888 and current year)
            - Detect whether the file is a movie or a TV show episode (look for patterns like S01E03, 1x03, etc.)

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

            Input:  randomgarbage_xyz.txt
            Output:
            error: Input does not appear to be a movie or TV show filename.
            """;

    private final ChatClient chatClient;
    private final SimpMessagingTemplate messagingTemplate;
    private final LlmResponseParser responseParser;
    private final MovieHandler movieHandler;
    private final ShowHandler showHandler;

    public ChatStreamController(ChatClient.Builder builder, SimpMessagingTemplate messagingTemplate,
            LlmResponseParser responseParser, MovieHandler movieHandler, ShowHandler showHandler) {
        this.chatClient = builder.build();
        this.messagingTemplate = messagingTemplate;
        this.responseParser = responseParser;
        this.movieHandler = movieHandler;
        this.showHandler = showHandler;
    }

    @MessageMapping("/message")
    public void handleChatMessage(String userMessage) {
        StringBuilder fullResponse = new StringBuilder();

        chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userMessage)
                .stream()
                .content()
                .doOnNext(token -> fullResponse.append(token))
                .doOnComplete(() -> {
                    MediaMetadata metadata = responseParser.parse(fullResponse.toString());
                    if (metadata.isError()) {
                        log.warn("LLM could not parse filename: {}", metadata.error());
                    } else if (metadata.isMovie()) {
                        movieHandler.handle(metadata);
                    } else if (metadata.isShow()) {
                        showHandler.handle(metadata);
                    } else {
                        log.warn("Unknown media type in LLM response: {}", fullResponse);
                    }
                })
                .subscribe(token -> {
                    // Pushes each word/token to the UI as it arrives
                    messagingTemplate.convertAndSend("/topic/replies", token);
                });
    }
}

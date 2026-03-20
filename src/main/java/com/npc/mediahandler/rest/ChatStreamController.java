package com.npc.mediahandler.rest;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

import com.npc.mediahandler.llm.FilenameParserService;
import com.npc.mediahandler.media.LlmResponseParser;
import com.npc.mediahandler.media.MediaMetadata;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Controller
public class ChatStreamController {

    private final ChatClient chatClient;
    private final SimpMessagingTemplate messagingTemplate;
    private final LlmResponseParser responseParser;

    public ChatStreamController(ChatClient.Builder builder, SimpMessagingTemplate messagingTemplate,
            LlmResponseParser responseParser) {
        this.chatClient = builder.build();
        this.messagingTemplate = messagingTemplate;
        this.responseParser = responseParser;
    }

    @MessageMapping("/message")
    public void handleChatMessage(String userMessage) {
        StringBuilder fullResponse = new StringBuilder();

        chatClient.prompt()
                .system(FilenameParserService.SYSTEM_PROMPT)
                .user(userMessage)
                .stream()
                .content()
                .doOnNext(token -> fullResponse.append(token))
                .doOnComplete(() -> {
                    MediaMetadata metadata = responseParser.parse(fullResponse.toString());
                    if (metadata.isError()) {
                        log.warn("LLM could not parse filename: {}", metadata.error());
                    } else {
                        log.info("UI parse result: type={}, name={}, year={}", metadata.type(), metadata.name(), metadata.year());
                    }
                })
                .subscribe(token -> {
                    // Pushes each word/token to the UI as it arrives
                    messagingTemplate.convertAndSend("/topic/replies", token);
                });
    }
}

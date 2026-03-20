package com.npc.mediahandler.llm;

import java.time.Duration;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import com.npc.mediahandler.config.AppConfigService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.netty.http.client.HttpClient;

import static com.npc.mediahandler.config.AppConfigService.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicChatClientProvider {

    private final AppConfigService configService;

    public ChatClient getChatClient() {
        String provider = configService.getOrDefault(LLM_PROVIDER, "openai");
        String apiKey   = configService.getOrDefault(LLM_API_KEY, "ollama");
        String baseUrl  = configService.getOrDefault(LLM_BASE_URL, "http://localhost:11434");
        String model    = configService.getOrDefault(LLM_MODEL, "qwen2.5:14b");

        ChatModel chatModel;
        if ("anthropic".equalsIgnoreCase(provider)) {
            log.debug("Building Anthropic ChatClient with model={}", model);
            AnthropicApi anthropicApi = AnthropicApi.builder().apiKey(apiKey).build();
            chatModel = AnthropicChatModel.builder()
                    .anthropicApi(anthropicApi)
                    .defaultOptions(AnthropicChatOptions.builder().model(model).build())
                    .build();
        } else {
            log.debug("Building OpenAI ChatClient with baseUrl={}, model={}", baseUrl, model);
            WebClient.Builder webClientBuilder = WebClient.builder()
                    .clientConnector(new ReactorClientHttpConnector(
                            HttpClient.create().responseTimeout(Duration.ofMinutes(15))));
            OpenAiApi openAiApi = OpenAiApi.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .webClientBuilder(webClientBuilder)
                    .build();
            chatModel = OpenAiChatModel.builder()
                    .openAiApi(openAiApi)
                    .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                    .build();
        }

        return ChatClient.builder(chatModel).build();
    }
}

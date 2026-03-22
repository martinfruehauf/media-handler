package com.npc.mediahandler.llm;

import java.time.Duration;
import java.util.Objects;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.ReactorClientHttpRequestFactory;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
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

    private ChatClient cachedClient;
    private String cachedProvider;
    private String cachedApiKey;
    private String cachedBaseUrl;
    private String cachedModel;

    public synchronized ChatClient getChatClient() {
        String provider = configService.getOrDefault(LLM_PROVIDER, "openai");
        String apiKey   = configService.getOrDefault(LLM_API_KEY, "ollama");
        String baseUrl  = configService.getLlmBaseUrl();
        String model    = configService.getOrDefault(LLM_MODEL, "qwen2.5:14b");

        if (cachedClient != null
                && Objects.equals(provider, cachedProvider)
                && Objects.equals(apiKey,   cachedApiKey)
                && Objects.equals(baseUrl,  cachedBaseUrl)
                && Objects.equals(model,    cachedModel)) {
            return cachedClient;
        }

        log.info("Building ChatClient: provider={}, baseUrl={}, model={}", provider, baseUrl, model);

        ChatModel chatModel;
        if ("anthropic".equalsIgnoreCase(provider)) {
            AnthropicApi anthropicApi = AnthropicApi.builder().apiKey(apiKey).build();
            chatModel = AnthropicChatModel.builder()
                    .anthropicApi(anthropicApi)
                    .defaultOptions(AnthropicChatOptions.builder().model(model).build())
                    .build();
        } else {
            HttpClient httpClient = HttpClient.create().responseTimeout(Duration.ofMinutes(15));
            RestClient.Builder restClientBuilder = RestClient.builder()
                    .requestFactory(new ReactorClientHttpRequestFactory(httpClient));
            WebClient.Builder webClientBuilder = WebClient.builder()
                    .clientConnector(new ReactorClientHttpConnector(httpClient));
            OpenAiApi openAiApi = OpenAiApi.builder()
                    .baseUrl(baseUrl)
                    .apiKey(apiKey)
                    .restClientBuilder(restClientBuilder)
                    .webClientBuilder(webClientBuilder)
                    .build();
            chatModel = OpenAiChatModel.builder()
                    .openAiApi(openAiApi)
                    .defaultOptions(OpenAiChatOptions.builder().model(model).build())
                    .build();
        }

        cachedClient  = ChatClient.builder(chatModel).build();
        cachedProvider = provider;
        cachedApiKey   = apiKey;
        cachedBaseUrl  = baseUrl;
        cachedModel    = model;

        return cachedClient;
    }

    /** Call this after saving new LLM settings so the next request rebuilds the client. */
    public synchronized void invalidate() {
        cachedClient = null;
    }
}

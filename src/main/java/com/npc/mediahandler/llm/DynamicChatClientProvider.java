package com.npc.mediahandler.llm;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.api.AnthropicApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;
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

    /** GBNF grammar that forces the local model into the line format LlmResponseParser expects. */
    private static final String FILENAME_GRAMMAR = loadGrammar();

    /** Enough for the longest allowed response (name is capped at 120 chars by the grammar). */
    private static final int LOCAL_MAX_TOKENS = 128;

    private ChatClient cachedClient;
    private List<String> cachedSettings;

    public synchronized ChatClient getChatClient() {
        boolean local  = configService.isLocalLlm();
        String provider = local ? "local" : configService.getOrDefault(LLM_PROVIDER, "openai");
        String apiKey   = local ? "local" : configService.getOrDefault(LLM_API_KEY, "ollama");
        String baseUrl  = local
                ? "http://127.0.0.1:" + configService.getInt(LLM_LOCAL_PORT, 8081)
                : configService.getLlmBaseUrl();
        String model    = local
                ? configService.getOrDefault(LLM_LOCAL_MODEL_NAME, "local")
                : configService.getOrDefault(LLM_MODEL, "qwen2.5:14b");

        List<String> settings = List.of(provider, apiKey, baseUrl, model);
        if (cachedClient != null && settings.equals(cachedSettings)) {
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
            OpenAiChatOptions options = local
                    ? OpenAiChatOptions.builder()
                            .model(model)
                            .temperature(0.0)
                            .maxTokens(LOCAL_MAX_TOKENS)
                            .extraBody(Map.of("grammar", FILENAME_GRAMMAR))  // llama-server extension
                            .build()
                    : OpenAiChatOptions.builder().model(model).build();
            OpenAiChatModel.Builder builder = OpenAiChatModel.builder()
                    .openAiApi(openAiApi(baseUrl, apiKey))
                    .defaultOptions(options);
            if (local) {
                // Fail fast: a dead local server should mark the file LLM_FAILED, not stall the queue in backoff
                builder.retryTemplate(new RetryTemplate(RetryPolicy.withMaxRetries(0)));
            }
            chatModel = builder.build();
        }

        cachedClient   = ChatClient.builder(chatModel).build();
        cachedSettings = settings;

        return cachedClient;
    }

    private static OpenAiApi openAiApi(String baseUrl, String apiKey) {
        HttpClient httpClient = HttpClient.create().responseTimeout(Duration.ofMinutes(15));
        RestClient.Builder restClientBuilder = RestClient.builder()
                .requestFactory(new ReactorClientHttpRequestFactory(httpClient));
        WebClient.Builder webClientBuilder = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient));
        return OpenAiApi.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .restClientBuilder(restClientBuilder)
                .webClientBuilder(webClientBuilder)
                .build();
    }

    private static String loadGrammar() {
        try {
            return new ClassPathResource("llm/filename-metadata.gbnf").getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load llm/filename-metadata.gbnf", e);
        }
    }

    /** Call this after saving new LLM settings so the next request rebuilds the client. */
    public synchronized void invalidate() {
        cachedClient = null;
    }
}

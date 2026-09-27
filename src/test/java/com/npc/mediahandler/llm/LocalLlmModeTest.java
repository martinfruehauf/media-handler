package com.npc.mediahandler.llm;

import static com.npc.mediahandler.config.AppConfigService.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.npc.mediahandler.config.AppConfig;
import com.npc.mediahandler.config.AppConfigRepository;
import com.npc.mediahandler.config.AppConfigService;
import com.npc.mediahandler.config.MediaProperties;
import com.npc.mediahandler.media.LlmResponseParser;
import com.npc.mediahandler.media.MediaMetadata;

/**
 * Runs the local-mode pipeline against a fake llama-server (a small Python HTTP server)
 * that records its command line and the chat request body.
 */
class LocalLlmModeTest {

    private static final String FAKE_SERVER = """
            #!/usr/bin/env python3
            import json, sys
            from http.server import BaseHTTPRequestHandler, HTTPServer
            args = sys.argv[1:]
            out = args[args.index("--model") + 1] + ".out"
            open(out + ".args", "w").write(" ".join(args))
            class H(BaseHTTPRequestHandler):
                def log_message(self, *a): pass
                def reply(self, body):
                    data = json.dumps(body).encode()
                    self.send_response(200)
                    self.send_header("Content-Type", "application/json")
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
                def do_GET(self):
                    self.reply({"status": "ok"})
                def do_POST(self):
                    if self.headers["Content-Length"]:
                        body = self.rfile.read(int(self.headers["Content-Length"]))
                    else:  # chunked
                        body = b""
                        while (size := int(self.rfile.readline().strip(), 16)) > 0:
                            body += self.rfile.read(size)
                            self.rfile.readline()
                        self.rfile.readline()
                    open(out + ".request", "wb").write(body)
                    content = "type: show\\nname: Breaking Bad\\nyear:\\nseason: S03\\nepisode: E07"
                    self.reply({"id": "x", "object": "chat.completion", "created": 0, "model": "m",
                                "choices": [{"index": 0, "finish_reason": "stop",
                                             "message": {"role": "assistant", "content": content}}],
                                "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}})
            HTTPServer(("127.0.0.1", int(args[args.index("--port") + 1])), H).serve_forever()
            """;

    @TempDir Path dir;

    private final Map<String, String> config = new HashMap<>();
    private LocalLlmServerManager manager;
    private FilenameParserService parser;
    private Path model;

    @BeforeEach
    void setUp() throws IOException {
        Path binary = dir.resolve("llama-server");
        Files.writeString(binary, FAKE_SERVER);
        Files.setPosixFilePermissions(binary, PosixFilePermissions.fromString("rwx------"));
        model = Files.writeString(dir.resolve("model.gguf"), "fake");

        config.put(LLM_MODE, LLM_MODE_LOCAL);
        config.put(LLM_LOCAL_SERVER_BINARY, binary.toString());
        config.put(LLM_LOCAL_MODEL_PATH, model.toString());
        config.put(LLM_LOCAL_MODEL_NAME, "test-model");
        config.put(LLM_LOCAL_THREADS, "3");
        config.put(LLM_LOCAL_PORT, String.valueOf(freePort()));
        config.put(LLM_LOCAL_IDLE_TIMEOUT, "600");
        config.put(LLM_LOCAL_STARTUP_TIMEOUT, "20");

        AppConfigRepository repository = mock(AppConfigRepository.class);
        when(repository.findById(anyString())).thenAnswer(inv ->
                Optional.ofNullable(config.get(inv.<String>getArgument(0)))
                        .map(v -> new AppConfig(inv.getArgument(0), v)));
        AppConfigService configService = new AppConfigService(repository, new MediaProperties());

        manager = new LocalLlmServerManager(configService);
        parser = new FilenameParserService(new DynamicChatClientProvider(configService), new LlmResponseParser(),
                mock(WolService.class), manager, configService);
    }

    @AfterEach
    void tearDown() {
        manager.shutdown();
    }

    @Test
    void startsServerOnDemandAndSendsGrammar() throws IOException {
        assertThat(manager.getState()).isEqualTo(LocalLlmServerManager.State.STOPPED);

        MediaMetadata result = parser.parse("Breaking.Bad.S03E07.German.BluRay.x264.mkv");

        assertThat(result.error()).isNull();
        assertThat(result.isShow()).isTrue();
        assertThat(result.name()).isEqualTo("Breaking Bad");
        assertThat(result.season()).isEqualTo("S03");
        assertThat(result.episode()).isEqualTo("E07");
        assertThat(manager.getState()).isEqualTo(LocalLlmServerManager.State.RUNNING);

        String args = Files.readString(Path.of(model + ".out.args"));
        assertThat(args).contains("--threads 3", "--parallel 1", "--host 127.0.0.1", "--alias test-model");

        String request = Files.readString(Path.of(model + ".out.request"));
        assertThat(request)
                .contains("\"grammar\":\"")
                .contains("root    ::= movie | show | error")
                .contains("\"model\":\"test-model\"")
                .contains("\"temperature\":0.0");
    }

    @Test
    void stopsServerAfterIdleTimeout() throws InterruptedException {
        config.put(LLM_LOCAL_IDLE_TIMEOUT, "1");

        parser.parse("Some.Movie.2005.1080p.mkv");

        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (manager.getState() != LocalLlmServerManager.State.STOPPED && Instant.now().isBefore(deadline)) {
            Thread.sleep(100);
        }
        assertThat(manager.getState()).isEqualTo(LocalLlmServerManager.State.STOPPED);
    }

    @Test
    void missingModelFailsTheParseInsteadOfThrowing() {
        config.put(LLM_LOCAL_MODEL_PATH, dir.resolve("missing.gguf").toString());

        MediaMetadata result = parser.parse("Some.Movie.2005.1080p.mkv");

        assertThat(result.isError()).isTrue();
        assertThat(FilenameParserService.isUnavailable(result)).isTrue();
        assertThat(result.error()).startsWith("Local LLM unavailable: Model file not found");
        assertThat(manager.getState()).isEqualTo(LocalLlmServerManager.State.FAILED);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

package com.npc.mediahandler.llm;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import com.npc.mediahandler.config.AppConfigService;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import static com.npc.mediahandler.config.AppConfigService.*;

/**
 * Runs llama.cpp's llama-server as a child process for local LLM mode.
 *
 * <p>The server is started on the first request and stopped after
 * {@code llm.local.idle-timeout-seconds} without requests (0 = keep running), mirroring the
 * wake → use → idle-shutdown lifecycle of {@link WolService}. It binds to 127.0.0.1 only.
 * If something is already answering on the configured port (e.g. a llama-server run by
 * systemd), that server is used as-is and never stopped by this class.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalLlmServerManager {

    public enum State { STOPPED, STARTING, RUNNING, EXTERNAL, FAILED }

    private static final int HEALTH_POLL_INTERVAL_MS = 500;
    private static final int STOP_GRACE_SECONDS      = 10;

    private final AppConfigService configService;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "llama-server-idle");
        t.setDaemon(true);
        return t;
    });
    private final AtomicInteger activeRequests = new AtomicInteger(0);

    private Process process;
    private ScheduledFuture<?> pendingStop;
    private volatile State state = State.STOPPED;
    private volatile String lastError;
    private volatile boolean restartPending;

    public State getState() {
        if (state == State.RUNNING && (process == null || !process.isAlive())) {
            return State.FAILED;
        }
        return state;
    }

    public String getStatusMessage() {
        String model = configService.get(LLM_LOCAL_MODEL_NAME);
        return switch (getState()) {
            case STOPPED  -> "Idle — local model starts on demand";
            case STARTING -> "Loading local model " + model + "…";
            case RUNNING  -> "Local model " + model + " running";
            case EXTERNAL -> "Using llama-server already running on port " + port();
            case FAILED   -> lastError != null ? lastError : "llama-server exited unexpectedly";
        };
    }

    /**
     * Returns a human-readable problem with the local setup (missing binary or model),
     * or {@code null} if the files are in place.
     */
    public String checkInstallation() {
        Path binary = Paths.get(configService.getOrDefault(LLM_LOCAL_SERVER_BINARY, ""));
        Path model  = Paths.get(configService.getOrDefault(LLM_LOCAL_MODEL_PATH, ""));
        if (!Files.isExecutable(binary)) return "llama-server not found or not executable: " + binary;
        if (!Files.isReadable(model))    return "Model file not found or not readable: " + model;
        return null;
    }

    /**
     * Call after acquiring the LLM semaphore, before sending a request. Starts llama-server if
     * needed and blocks until it reports healthy. Throws if it cannot be started.
     */
    public void beforeLlmRequest() {
        activeRequests.incrementAndGet();
        cancelStop();
        ensureRunning();
    }

    /** Call in the finally block after the request. Schedules the idle stop. */
    public void afterLlmRequest() {
        if (activeRequests.decrementAndGet() > 0) return;
        if (restartPending) {
            stop();
            return;
        }
        int idleSeconds = configService.getInt(LLM_LOCAL_IDLE_TIMEOUT, 600);
        if (idleSeconds > 0 && state == State.RUNNING) {
            scheduleStop(idleSeconds);
        }
    }

    /**
     * Call after local server settings change. Stops the server now, or after the in-flight
     * request finishes; the next request starts it with the new settings.
     */
    public void restart() {
        if (activeRequests.get() > 0) {
            restartPending = true;
        } else {
            stop();
        }
    }

    @PreDestroy
    public void shutdown() {
        stop();
        scheduler.shutdownNow();
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private synchronized void ensureRunning() {
        if (state == State.RUNNING && process != null && process.isAlive()) return;
        if (state == State.EXTERNAL && isHealthy()) return;

        if (process == null && isHealthy()) {
            log.info("llama-server already running on port {} (not started by MediaHandler) — using it", port());
            state = State.EXTERNAL;
            return;
        }
        if (process != null) {
            log.warn("llama-server process died (exit code {}) — restarting", exitCodeOf(process));
            process = null;
        }

        String problem = checkInstallation();
        if (problem != null) fail(problem);

        List<String> command = buildCommand();
        File logFile = Paths.get("data", "llama-server.log").toFile();
        log.info("Starting llama-server: {}", String.join(" ", command));
        state = State.STARTING;
        try {
            Files.createDirectories(logFile.toPath().getParent());
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .redirectOutput(logFile)  // overwritten on each start; holds the current run's output
                    .start();
        } catch (Exception e) {
            process = null;
            fail("Could not start llama-server: " + e.getMessage());
        }

        int timeoutSeconds = configService.getInt(LLM_LOCAL_STARTUP_TIMEOUT, 120);
        Instant started  = Instant.now();
        Instant deadline = started.plusSeconds(timeoutSeconds);
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                int exitCode = exitCodeOf(process);
                process = null;
                fail("llama-server exited with code " + exitCode + " during startup — see " + logFile.getAbsolutePath());
            }
            if (isHealthy()) {
                state = State.RUNNING;
                restartPending = false;
                log.info("llama-server ready after {} ms", Duration.between(started, Instant.now()).toMillis());
                return;
            }
            try {
                Thread.sleep(HEALTH_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                stop();
                fail("Interrupted while waiting for llama-server");
            }
        }
        stop();
        fail("llama-server did not become healthy within " + timeoutSeconds + "s — see " + logFile.getAbsolutePath());
    }

    private List<String> buildCommand() {
        List<String> cmd = new ArrayList<>(List.of(
                configService.get(LLM_LOCAL_SERVER_BINARY),
                "--model",    configService.get(LLM_LOCAL_MODEL_PATH),
                "--alias",    configService.getOrDefault(LLM_LOCAL_MODEL_NAME, "local"),
                "--host",     "127.0.0.1",
                "--port",     String.valueOf(port()),
                "--threads",  String.valueOf(configService.getInt(LLM_LOCAL_THREADS, 4)),
                "--ctx-size", String.valueOf(configService.getInt(LLM_LOCAL_CTX_SIZE, 2048)),
                // One slot: requests are serialised anyway, and multiple slots would split the context
                "--parallel", "1"
        ));
        String extra = configService.get(LLM_LOCAL_EXTRA_ARGS);
        if (StringUtils.isNotBlank(extra)) {
            cmd.addAll(List.of(StringUtils.split(extra.strip())));
        }
        return cmd;
    }

    private synchronized void stop() {
        cancelStop();
        restartPending = false;
        if (process != null) {
            log.info("Stopping llama-server");
            process.destroy();
            try {
                if (!process.waitFor(STOP_GRACE_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("llama-server did not exit within {}s — killing it", STOP_GRACE_SECONDS);
                    process.destroyForcibly();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
            process = null;
        }
        state = State.STOPPED;
    }

    private void idleStop() {
        if (activeRequests.get() > 0) {
            log.debug("llama-server idle stop skipped — request in flight");
            return;
        }
        log.info("llama-server idle — stopping to free memory");
        stop();
    }

    private synchronized void scheduleStop(int idleSeconds) {
        cancelStop();
        log.debug("Scheduling llama-server stop in {} seconds", idleSeconds);
        pendingStop = scheduler.schedule(this::idleStop, idleSeconds, TimeUnit.SECONDS);
    }

    private synchronized void cancelStop() {
        if (pendingStop != null && !pendingStop.isDone()) {
            pendingStop.cancel(false);
        }
        pendingStop = null;
    }

    private void fail(String message) {
        log.error(message);
        lastError = message;
        state = State.FAILED;
        throw new IllegalStateException(message);
    }

    private boolean isHealthy() {
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL("http://127.0.0.1:" + port() + "/health").openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(2000);
            int code = conn.getResponseCode();  // 503 while the model is loading
            conn.disconnect();
            return code == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private int port() {
        return configService.getInt(LLM_LOCAL_PORT, 8081);
    }

    private static int exitCodeOf(Process p) {
        try {
            return p.exitValue();
        } catch (IllegalThreadStateException e) {
            return -1;
        }
    }
}

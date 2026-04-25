package com.npc.mediahandler.llm;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Map;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Service;

import com.npc.mediahandler.config.AppConfigService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class WolService {

    public enum WolState { IDLE, WAKING, AWAKE, FAILED }

    private static final int WAKE_POLL_INTERVAL_SECONDS = 5;
    private static final int WAKE_TIMEOUT_MINUTES       = 4;
    private static final int SHUTDOWN_DELAY_SECONDS     = 300; // 5 minutes idle before shutdown

    private final AppConfigService configService;

    private volatile WolState state = WolState.IDLE;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "wol-scheduler");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pendingShutdown;
    private final AtomicInteger activeRequests = new AtomicInteger(0);

    public WolState getState() {
        return state;
    }

    public String getStatusMessage() {
        return switch (state) {
            case IDLE   -> "Reachable";
            case WAKING -> "Waking LLM machine via WOL\u2026";
            case AWAKE  -> "Running (woken via WOL)";
            case FAILED -> "WOL failed \u2014 LLM unreachable";
        };
    }

    /**
     * Call this after acquiring the LLM semaphore, before sending a request.
     * If WOL is enabled and the LLM is unreachable, sends a magic packet and
     * blocks until the machine responds (up to {@value WAKE_TIMEOUT_MINUTES} min).
     */
    public void beforeLlmRequest() {
        activeRequests.incrementAndGet();
        cancelShutdown();

        if (!"true".equals(configService.get(AppConfigService.LLM_WOL_ENABLED))) return;
        if (state == WolState.AWAKE) {
            log.debug("WOL: machine already awake, skipping wake");
            return;
        }
        if (isLlmReachable()) {
            log.info("WOL: LLM reachable without waking (machine already on)");
        } else {
            log.info("WOL: LLM unreachable — triggering wake sequence");
            doWake();
        }
    }

    /**
     * Call this in the finally block after releasing the LLM semaphore.
     * Schedules an automatic machine shutdown after a period of inactivity.
     */
    public void afterLlmRequest() {
        int remaining = activeRequests.decrementAndGet();
        if (remaining == 0 && (state == WolState.AWAKE || state == WolState.FAILED)) {
            scheduleShutdown();
        }
    }

    /** Returns the shutdown command that will be used (custom or auto-derived). */
    public String getResolvedShutdownCmd() {
        return buildShutdownCmd();
    }

    /**
     * Sends a WOL magic packet immediately without waiting for the machine to respond.
     * Used by the "Test Wake" button — the health indicator will update as the machine comes up.
     */
    public Map<String, Object> sendWakePacketNow() {
        String mac = configService.getOrDefault(AppConfigService.LLM_WOL_MAC, "b4:a9:fc:cd:58:88");
        try {
            sendMagicPacket(mac);
            state = WolState.WAKING;
            log.info("Manual WOL wake packet sent to {}", mac);
            return Map.of("ok", true, "message", "Magic packet sent to " + mac + " — watch the LLM indicator");
        } catch (Exception e) {
            log.error("Failed to send WOL packet: {}", e.getMessage());
            return Map.of("ok", false, "message", e.getMessage());
        }
    }

    /**
     * Runs the shutdown command immediately (bypasses the idle timer).
     * Used by the settings UI "Test Shutdown" button.
     */
    public Map<String, Object> runShutdownNow() {
        String cmd = buildShutdownCmd();
        try {
            String[] parts = cmd.split("\\s+");
            Process process = new ProcessBuilder(parts)
                    .redirectErrorStream(true)
                    .start();

            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader =
                         new java.io.BufferedReader(new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }

            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            String out = output.toString().trim();

            if (!finished) {
                process.destroyForcibly();
                return Map.of("ok", false, "command", cmd, "output", out,
                              "error", "Command timed out after 15s");
            }
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                state = WolState.IDLE;
                return Map.of("ok", true, "command", cmd, "output", out, "exitCode", exitCode);
            }
            return Map.of("ok", false, "command", cmd, "output", out, "exitCode", exitCode,
                          "error", "Command exited with code " + exitCode);
        } catch (Exception e) {
            return Map.of("ok", false, "command", cmd, "output", "", "error", e.getMessage());
        }
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private synchronized void doWake() {
        if (state == WolState.AWAKE) return;
        if (isLlmReachable()) return;

        String mac = configService.getOrDefault(AppConfigService.LLM_WOL_MAC, "b4:a9:fc:cd:58:88");
        log.info("Sending WOL magic packet to {} (native Java UDP broadcast)", mac);
        state = WolState.WAKING;

        try {
            sendMagicPacket(mac);
        } catch (Exception e) {
            log.warn("Failed to send WOL magic packet: {}", e.getMessage());
            // Continue polling anyway — packet may still have arrived
        }

        Instant deadline = Instant.now().plus(Duration.ofMinutes(WAKE_TIMEOUT_MINUTES));
        int attempt = 0;
        while (Instant.now().isBefore(deadline)) {
            try {
                Thread.sleep(WAKE_POLL_INTERVAL_SECONDS * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                state = WolState.FAILED;
                return;
            }
            attempt++;
            if (isLlmReachable()) {
                log.info("LLM machine is up after WOL wake (attempt {})", attempt);
                state = WolState.AWAKE;
                return;
            }
            long secondsLeft = Duration.between(Instant.now(), deadline).getSeconds();
            log.info("Waiting for LLM machine to come up... (poll #{}, {}s remaining)", attempt, secondsLeft);
        }

        log.warn("LLM machine did not respond within {} minutes after WOL", WAKE_TIMEOUT_MINUTES);
        state = WolState.FAILED;
    }

    /**
     * Sends a Wake-on-LAN magic packet via UDP broadcast (no external binary required).
     * The magic packet is: 6×0xFF followed by the target MAC repeated 16 times.
     */
    private void sendMagicPacket(String mac) throws Exception {
        byte[] macBytes = parseMac(mac);
        byte[] packet   = new byte[6 + 16 * 6];
        for (int i = 0; i < 6; i++) packet[i] = (byte) 0xFF;
        for (int i = 0; i < 16; i++) System.arraycopy(macBytes, 0, packet, 6 + i * 6, 6);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            InetAddress broadcast = InetAddress.getByName("255.255.255.255");
            socket.send(new DatagramPacket(packet, packet.length, broadcast, 9));
        }
    }

    private byte[] parseMac(String mac) {
        String[] parts = mac.split("[:\\-]");
        if (parts.length != 6) throw new IllegalArgumentException("Invalid MAC address: " + mac);
        byte[] bytes = new byte[6];
        for (int i = 0; i < 6; i++) bytes[i] = (byte) Integer.parseInt(parts[i], 16);
        return bytes;
    }

    private synchronized void scheduleShutdown() {
        cancelShutdown();
        log.info("Scheduling LLM machine shutdown in {} seconds", SHUTDOWN_DELAY_SECONDS);
        pendingShutdown = scheduler.schedule(this::executeShutdown, SHUTDOWN_DELAY_SECONDS, TimeUnit.SECONDS);
    }

    private void cancelShutdown() {
        if (pendingShutdown != null && !pendingShutdown.isDone()) {
            pendingShutdown.cancel(false);
            log.debug("Cancelled pending LLM machine shutdown");
            pendingShutdown = null;
        }
    }

    private void executeShutdown() {
        if (activeRequests.get() > 0) {
            log.info("LLM shutdown deferred — requests still active");
            return;
        }
        String cmd = buildShutdownCmd();
        log.info("Running LLM machine shutdown command: {}", cmd);
        try {
            String[] parts = cmd.split("\\s+");
            Process process = new ProcessBuilder(parts)
                    .redirectErrorStream(true)
                    .start();

            // Read output before waitFor to avoid subprocess blocking on full buffer
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) output.append(line).append('\n');
            }

            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!output.isEmpty()) log.info("Shutdown command output: {}", output.toString().trim());

            if (!finished) {
                log.warn("Shutdown command timed out after 15s — machine may still be running");
                process.destroyForcibly();
                return;
            }
            int exitCode = process.exitValue();
            if (exitCode == 0) {
                log.info("LLM machine shutdown initiated successfully");
                state = WolState.IDLE;
            } else {
                log.error("Shutdown command exited with code {} — machine may still be running. " +
                          "Make sure SSH key auth is set up from this host to the LLM machine " +
                          "(ssh-copy-id) and the user can run 'sudo shutdown' without a password.", exitCode);
            }
        } catch (Exception e) {
            log.error("Failed to run LLM machine shutdown command '{}': {}", cmd, e.getMessage());
        }
    }

    private String buildShutdownCmd() {
        String custom = configService.get(AppConfigService.LLM_WOL_SHUTDOWN_CMD);
        if (custom != null && !custom.isBlank()) return custom;

        String host = "192.168.178.81";
        try { host = new URL(configService.getLlmBaseUrl()).getHost(); } catch (Exception ignored) {}

        String user = configService.getOrDefault(AppConfigService.LLM_WOL_SSH_USER, "martin");
        String keyPath = getSshKeyPath().toAbsolutePath().toString();

        return "ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null" +
               " -o ConnectTimeout=5 -i " + keyPath +
               " " + user + "@" + host + " sudo shutdown -h now";
    }

    /** Path to the app-managed SSH private key used for WOL shutdown. */
    java.nio.file.Path getSshKeyPath() {
        return java.nio.file.Paths.get("data", "wol_id_ed25519");
    }

    /**
     * Returns the public key content, generating the key pair first if it doesn't exist.
     * The public key must be added to the LLM machine's ~/.ssh/authorized_keys once.
     */
    public String getOrCreateSshPublicKey() {
        java.nio.file.Path keyPath = getSshKeyPath();
        java.nio.file.Path pubPath = java.nio.file.Paths.get(keyPath + ".pub");
        try {
            if (!java.nio.file.Files.exists(keyPath)) {
                java.nio.file.Files.createDirectories(keyPath.getParent());
                Process p = new ProcessBuilder(
                        "ssh-keygen", "-t", "ed25519", "-N", "", "-f", keyPath.toAbsolutePath().toString())
                        .redirectErrorStream(true).start();
                p.waitFor(10, TimeUnit.SECONDS);
                log.info("Generated WOL SSH key at {}", keyPath.toAbsolutePath());
            }
            if (java.nio.file.Files.exists(pubPath)) {
                return java.nio.file.Files.readString(pubPath).trim();
            }
        } catch (Exception e) {
            log.error("Failed to generate SSH key: {}", e.getMessage());
        }
        return null;
    }

    private boolean isLlmReachable() {
        String provider = configService.getOrDefault(AppConfigService.LLM_PROVIDER, "openai");
        String baseUrl = "anthropic".equalsIgnoreCase(provider)
                ? "https://api.anthropic.com"
                : configService.getLlmBaseUrl();
        try {
            URL url = new URL(baseUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            conn.getResponseCode();
            conn.disconnect();
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}

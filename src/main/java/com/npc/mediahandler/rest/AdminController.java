package com.npc.mediahandler.rest;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.zip.ZipFile;

import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private static final String JAR_PATH = "/opt/mediahandler/media-handler.jar";
    private static final String JAR_URL  =
            "https://github.com/martinfruehauf/media-handler/releases/latest/download/media-handler.jar";

    private final ConfigurableApplicationContext context;

    @PostMapping("/update")
    public ResponseEntity<Map<String, String>> update() {
        Path currentJar = Paths.get(JAR_PATH);
        if (!Files.exists(currentJar)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "Update is only available in the production LXC deployment"));
        }
        log.info("Self-update requested");
        new Thread(() -> {
            Path tmpJar = Paths.get(JAR_PATH + ".tmp");
            try {
                Thread.sleep(500); // let HTTP response reach the client first
                log.info("Downloading update from {}", JAR_URL);
                HttpURLConnection conn = (HttpURLConnection) URI.create(JAR_URL).toURL().openConnection();
                conn.setInstanceFollowRedirects(true);
                int status = conn.getResponseCode();
                if (status != HttpURLConnection.HTTP_OK) {
                    throw new RuntimeException("Download failed: HTTP " + status);
                }
                try (InputStream in = conn.getInputStream()) {
                    Files.copy(in, tmpJar, StandardCopyOption.REPLACE_EXISTING);
                }
                // Verify the downloaded file is a valid ZIP/JAR before replacing
                try (ZipFile zip = new ZipFile(tmpJar.toFile())) {
                    if (zip.size() == 0) throw new RuntimeException("Downloaded JAR is empty");
                }
                log.info("Download verified ({} bytes) — replacing JAR and restarting",
                        Files.size(tmpJar));
                Files.move(tmpJar, currentJar,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                // Force non-zero exit so systemd restarts the service
                System.exit(1);
            } catch (Exception e) {
                log.error("Self-update failed — keeping existing JAR", e);
                try { Files.deleteIfExists(tmpJar); } catch (Exception ignored) {}
            }
        }, "self-update").start();

        return ResponseEntity.accepted().body(Map.of("message",
                "Update started — service will restart in ~30s"));
    }
}

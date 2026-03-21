package com.npc.mediahandler.rest;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.npc.mediahandler.monitor.FileMonitorService;
import com.npc.mediahandler.processing.ProcessingGateService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/control")
@RequiredArgsConstructor
public class ProcessingControlController {

    private final ProcessingGateService gate;
    private final FileMonitorService fileMonitorService;

    @GetMapping
    public Map<String, Boolean> getState() {
        return Map.of("running", gate.isRunning());
    }

    @PostMapping
    public Map<String, Boolean> setState(@RequestBody Map<String, Boolean> body) {
        boolean desired = Boolean.TRUE.equals(body.get("running"));
        if (desired) {
            log.info("Processing resumed — resetting file tracking and re-scanning source folder");
            fileMonitorService.resetTracking();
            gate.start();
        } else {
            log.info("Processing stopped");
            gate.stop();
        }
        return Map.of("running", gate.isRunning());
    }
}

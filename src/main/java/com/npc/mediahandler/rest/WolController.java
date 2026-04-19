package com.npc.mediahandler.rest;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.npc.mediahandler.llm.WolService;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/wol")
@RequiredArgsConstructor
public class WolController {

    private final WolService wolService;

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
            "state",           wolService.getState().name(),
            "message",         wolService.getStatusMessage(),
            "shutdownCommand", wolService.getResolvedShutdownCmd()
        );
    }

    @PostMapping("/test-shutdown")
    public Map<String, Object> testShutdown() {
        return wolService.runShutdownNow();
    }
}

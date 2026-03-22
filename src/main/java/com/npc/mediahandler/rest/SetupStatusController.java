package com.npc.mediahandler.rest;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.npc.mediahandler.config.AppConfigService;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class SetupStatusController {

    private final AppConfigService configService;

    @GetMapping("/api/setup-status")
    public Map<String, Boolean> status() {
        return Map.of("needsSetup", configService.needsSetup());
    }
}

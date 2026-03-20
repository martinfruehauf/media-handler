package com.npc.mediahandler.media;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MovieHandler implements MediaHandler {

    @Override
    public void handle(MediaMetadata metadata) {
        log.info("Movie detected: name='{}', year='{}'", metadata.name(), metadata.year());
        // TODO: persist or process movie metadata
    }
}

package com.npc.mediahandler.media;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class ShowHandler implements MediaHandler {

    @Override
    public void handle(MediaMetadata metadata) {
        log.info("Show detected: name='{}', year='{}', season='{}', episode='{}'",
                metadata.name(), metadata.year(), metadata.season(), metadata.episode());
        // TODO: persist or process show metadata
    }
}

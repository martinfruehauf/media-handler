package com.npc.mediahandler.processing;

import org.springframework.stereotype.Service;

/**
 * Global on/off switch for the processing pipeline.
 * When stopped, the file monitor and retry service will skip their scheduled work.
 * Starts in the running state.
 */
@Service
public class ProcessingGateService {

    private volatile boolean running = true;

    public boolean isRunning() {
        return running;
    }

    public void start() {
        running = true;
    }

    public void stop() {
        running = false;
    }
}

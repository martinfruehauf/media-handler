package com.npc.mediahandler.monitor;

import java.nio.file.Path;

import org.springframework.context.ApplicationEvent;

public class FileReadyEvent extends ApplicationEvent {

    private final Path file;

    public FileReadyEvent(Object source, Path file) {
        super(source);
        this.file = file;
    }

    public Path getFile() {
        return file;
    }
}

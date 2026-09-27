package com.npc.mediahandler.processing;

/**
 * One step of a processing attempt, in the order it happened. {@code outcome} is
 * {@value #OK}, {@value #FAIL} or {@value #INFO}; notes written by older versions have none.
 */
public record ProcessingNote(String step, String detail, String outcome) {

    public static final String OK   = "ok";
    public static final String FAIL = "fail";
    public static final String INFO = "info";

    public ProcessingNote(String step, String detail) {
        this(step, detail, INFO);
    }

    public static ProcessingNote ok(String step, String detail) {
        return new ProcessingNote(step, detail, OK);
    }

    public static ProcessingNote fail(String step, String detail) {
        return new ProcessingNote(step, detail, FAIL);
    }
}

package com.enrola.agent;

import java.util.List;

/**
 * What the agent said, and how it would arrive: {@code parts} is the message split so each piece
 * fits one SMS, and its size is what sending would cost.
 */
public record Reply(String text, List<String> parts) {

    /** Splits {@code text} into the messages it would take to send. */
    public static Reply of(String text) {
        List<String> parts = MessageSegmenter.split(text);
        return new Reply(String.join(" ", parts), parts);
    }

    public int segments() {
        return parts.size();
    }
}

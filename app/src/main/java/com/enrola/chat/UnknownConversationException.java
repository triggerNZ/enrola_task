package com.enrola.chat;

import java.util.UUID;

/**
 * No conversation with this id. Thrown rather than creating one on the fly: a single mistyped
 * character would otherwise produce an empty conversation that looks like lost history.
 */
public class UnknownConversationException extends RuntimeException {

    public UnknownConversationException(UUID id) {
        super("No conversation " + id + ".");
    }
}

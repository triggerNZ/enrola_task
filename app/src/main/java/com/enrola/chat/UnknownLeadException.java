package com.enrola.chat;

import java.util.UUID;

/** No lead with this id. */
public class UnknownLeadException extends RuntimeException {

    public UnknownLeadException(UUID id) {
        super("No lead " + id + ".");
    }
}

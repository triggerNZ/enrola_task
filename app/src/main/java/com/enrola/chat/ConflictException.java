package com.enrola.chat;

/**
 * The request was understood but the record is not in a state that allows it -- contacting a
 * lead twice, or one who never consented. Distinct from a bad request: nothing about the call
 * was wrong, and repeating it will not help.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}

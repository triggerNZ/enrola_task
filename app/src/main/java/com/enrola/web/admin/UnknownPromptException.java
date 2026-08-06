package com.enrola.web.admin;

/** A prompt kind or version that does not exist -- usually a stale link or a hand-typed URL. */
class UnknownPromptException extends RuntimeException {

    UnknownPromptException(String message) {
        super(message);
    }
}

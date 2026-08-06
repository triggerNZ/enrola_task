package com.enrola.agent;

/**
 * The call to the language model failed. Distinct from any other runtime failure so the API can
 * answer with a gateway error -- the fault is upstream, not in the request.
 */
public class LlmUnavailableException extends RuntimeException {

    public LlmUnavailableException(Throwable cause) {
        super("The language model call failed: " + cause.getMessage(), cause);
    }
}

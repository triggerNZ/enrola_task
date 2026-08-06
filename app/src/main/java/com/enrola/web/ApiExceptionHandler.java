package com.enrola.web;

import com.enrola.agent.LlmUnavailableException;
import com.enrola.chat.ConflictException;
import com.enrola.chat.UnknownConversationException;
import com.enrola.chat.UnknownLeadException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns the failures the chat can produce into status codes with a flat {@code {"error": ...}}
 * body -- flat so a shell client can read it with {@code jq -r .error}.
 */
/*
 * Scoped to the REST controllers by type, not by package: com.enrola.web.admin is a subpackage
 * of this one, and an unscoped advice would answer a broken admin page with a JSON body instead
 * of an error page.
 */
@RestControllerAdvice(assignableTypes = {ChatController.class, LeadController.class})
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    record ErrorResponse(String error) {}

    @ExceptionHandler({UnknownConversationException.class, UnknownLeadException.class})
    ResponseEntity<ErrorResponse> notFound(RuntimeException e) {
        return status(HttpStatus.NOT_FOUND, e.getMessage());
    }

    /** The record is not in a state that allows this: already contacted, no consent, opted out. */
    @ExceptionHandler(ConflictException.class)
    ResponseEntity<ErrorResponse> conflict(ConflictException e) {
        return status(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        HttpMessageNotReadableException.class,
        MethodArgumentTypeMismatchException.class,
        HandlerMethodValidationException.class
    })
    ResponseEntity<ErrorResponse> badRequest(Exception e) {
        return status(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** Bad gateway, not server error: the request was fine, the model upstream was not. */
    @ExceptionHandler(LlmUnavailableException.class)
    ResponseEntity<ErrorResponse> llmUnavailable(LlmUnavailableException e) {
        log.error("LLM call failed: {}", e.getMessage());
        return status(HttpStatus.BAD_GATEWAY, e.getMessage());
    }

    private static ResponseEntity<ErrorResponse> status(HttpStatus status, String message) {
        return ResponseEntity.status(status).body(new ErrorResponse(message));
    }
}

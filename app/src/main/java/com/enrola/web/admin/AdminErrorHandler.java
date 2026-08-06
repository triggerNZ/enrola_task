package com.enrola.web.admin;

import com.enrola.chat.UnknownConversationException;
import org.springframework.http.HttpStatus;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Failures on the admin pages, as pages.
 *
 * <p>Separate from {@code ApiExceptionHandler}, which answers in JSON and is scoped to the REST
 * controllers: somebody following a stale link should get something they can read and a way back,
 * not {@code {"error": ...}}.
 */
@ControllerAdvice(assignableTypes = {AdminController.class, PromptController.class})
class AdminErrorHandler {

    @ExceptionHandler({UnknownConversationException.class, UnknownPromptException.class})
    @ResponseStatus(HttpStatus.NOT_FOUND)
    String notFound(RuntimeException e, Model model) {
        model.addAttribute("message", e.getMessage());
        return "admin/error";
    }

    /** A rating outside one to five, or a comment longer than the column takes. */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    String badRequest(IllegalArgumentException e, Model model) {
        model.addAttribute("message", e.getMessage());
        return "admin/error";
    }
}

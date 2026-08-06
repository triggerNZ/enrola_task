package com.enrola.chat;

import java.util.Locale;
import java.util.Set;

/** The words that end a marketing conversation, and the normalisation applied to them. */
final class OptOut {

    private static final Set<String> REQUESTS =
            Set.of("stop", "stopall", "unsubscribe", "end", "quit", "optout", "opt out", "opt-out");

    private OptOut() {}

    static boolean matches(String text) {
        if (text == null) {
            return false;
        }
        String normalised = text.strip().toLowerCase(Locale.ROOT).replaceAll("[.!]+$", "");
        return REQUESTS.contains(normalised);
    }
}

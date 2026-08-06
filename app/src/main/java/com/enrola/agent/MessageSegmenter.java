package com.enrola.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits a reply into parts that would each fit one SMS.
 *
 * <p>An SMS holds 160 characters when every character is in the GSM 03.38 alphabet, and 70
 * otherwise, because anything outside it forces the whole message into UCS-2. Concatenated
 * messages give up a few characters per part to the header that reassembles them.
 *
 * <p>Nothing here sends anything. It exists so the agent is measured against the channel it is
 * writing for rather than trusted to be brief.
 */
final class MessageSegmenter {

    private static final int GSM_SINGLE = 160;
    private static final int GSM_CONCATENATED = 153;
    private static final int UCS2_SINGLE = 70;
    private static final int UCS2_CONCATENATED = 67;

    /**
     * The GSM 03.38 basic alphabet. Ordered as in the standard, though only membership matters
     * here. The escape-table characters are handled separately -- they cost two septets each.
     */
    private static final String GSM_BASIC =
            "@£$¥èéùìòÇ\nØø\rÅåΔ_ΦΓΛΩΠΨΣΘΞÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
                    + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§¿abcdefghijklmnopqrstuvwxyzäöñüà";

    private static final String GSM_EXTENDED = "^{}\\[~]|€";

    private MessageSegmenter() {}

    /** How many messages {@code text} would cost to send. */
    static int segments(String text) {
        return split(text).size();
    }

    /**
     * How many characters fit in {@code maxParts} messages. Quoted to the model as its budget,
     * so the number it is told and the number it is held to cannot drift apart.
     */
    static int budget(int maxParts) {
        return maxParts == 1 ? GSM_SINGLE : maxParts * GSM_CONCATENATED;
    }

    /**
     * Cuts {@code text} to fit {@code maxParts}, at the last sentence that fits. A reply cut
     * mid-sentence reads as a broken system; one cut a sentence early just reads as terse.
     */
    static String trimTo(String text, int maxParts) {
        List<String> parts = split(text);
        if (parts.size() <= maxParts) {
            return text;
        }

        String kept = String.join(" ", parts.subList(0, maxParts)).strip();
        int lastSentence = lastSentenceEnd(kept);
        return lastSentence > 0 ? kept.substring(0, lastSentence + 1) : kept;
    }

    /**
     * Cuts {@code text} to at most {@code maxCharacters}, at the last sentence that fits, or the
     * last whole word if there is no sentence end. The character-budget twin of {@link #trimTo},
     * for when something fixed has to be appended afterwards and needs the room reserved.
     */
    static String trimToLength(String text, int maxCharacters) {
        String trimmed = text == null ? "" : text.strip();
        boolean gsm = isGsm7(trimmed);
        if (length(trimmed, gsm) <= maxCharacters) {
            return trimmed;
        }

        String kept = takeUpTo(trimmed, maxCharacters, gsm).strip();
        int lastSentence = lastSentenceEnd(kept);
        return lastSentence > 0 ? kept.substring(0, lastSentence + 1) : kept;
    }

    private static int lastSentenceEnd(String text) {
        for (int i = text.length() - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == '.' || c == '!' || c == '?') {
                return i;
            }
        }
        return -1;
    }

    /**
     * The parts to send, in order. Splits between words, and only inside one when a single word
     * is longer than a whole message on its own.
     */
    static List<String> split(String text) {
        String trimmed = text == null ? "" : text.strip();
        if (trimmed.isEmpty()) {
            return List.of("");
        }

        boolean gsm = isGsm7(trimmed);
        int single = gsm ? GSM_SINGLE : UCS2_SINGLE;
        if (length(trimmed, gsm) <= single) {
            return List.of(trimmed);
        }

        int limit = gsm ? GSM_CONCATENATED : UCS2_CONCATENATED;
        List<String> parts = new ArrayList<>();
        String rest = trimmed;
        while (!rest.isEmpty()) {
            String part = takeUpTo(rest, limit, gsm);
            parts.add(part.strip());
            rest = rest.substring(part.length()).stripLeading();
        }
        return List.copyOf(parts);
    }

    /**
     * The longest prefix of {@code text} that fits, ending at a space where one is available.
     * Returned unstripped so the caller can measure how much was consumed.
     */
    private static String takeUpTo(String text, int limit, boolean gsm) {
        int end = 0;
        int cost = 0;
        while (end < text.length()) {
            int next = cost + charCost(text.charAt(end), gsm);
            if (next > limit) {
                break;
            }
            cost = next;
            end++;
        }
        if (end == text.length()) {
            return text;
        }

        int lastSpace = text.lastIndexOf(' ', end);
        // No space to break at means one word longer than a message: split it rather than
        // emitting a part that would be rejected by the network.
        return lastSpace > 0 ? text.substring(0, lastSpace) : text.substring(0, end);
    }

    private static boolean isGsm7(String text) {
        return text.chars()
                .allMatch(c -> GSM_BASIC.indexOf(c) >= 0 || GSM_EXTENDED.indexOf(c) >= 0);
    }

    /** How many message units {@code text} occupies, in whichever encoding it forces. */
    static int length(String text) {
        return length(text, isGsm7(text));
    }

    /**
     * How much a single message holds for this text: 160 septets, or 70 units once anything in it
     * forces UCS-2. One emoji more than halves the room, so a budget worked out from the GSM
     * numbers alone would be wrong by a factor of two.
     */
    static int singleCapacity(String text) {
        return isGsm7(text == null ? "" : text) ? GSM_SINGLE : UCS2_SINGLE;
    }

    private static int length(String text, boolean gsm) {
        int total = 0;
        for (int i = 0; i < text.length(); i++) {
            total += charCost(text.charAt(i), gsm);
        }
        return total;
    }

    /** Escape-table characters occupy two septets; everything else occupies one unit. */
    private static int charCost(char c, boolean gsm) {
        return gsm && GSM_EXTENDED.indexOf(c) >= 0 ? 2 : 1;
    }
}

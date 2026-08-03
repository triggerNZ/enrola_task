package com.enrola;

import dev.langchain4j.data.message.ChatMessage;
import java.util.List;
import java.util.Objects;

/**
 * Works out which messages in an incoming window are not yet in the transcript.
 *
 * <p>{@code MessageWindowChatMemory} hands a {@link dev.langchain4j.store.memory.chat.ChatMemoryStore}
 * only the current window, never the full history: it reads the stored messages, trims to
 * {@code maxMessages}, appends, trims again, and writes that slice back. A store that simply
 * replaced its rows with the slice would permanently discard the start of every long
 * conversation, since eviction is oldest-first. This class recovers the append.
 *
 * <p>Deliberately free of Spring and JDBC so the edge cases can be tested in milliseconds
 * without a database.
 */
final class MessageReconciler {

    private MessageReconciler() {}

    /**
     * Returns the messages in {@code window} that are not already in {@code stored}, in order.
     * Never returns null; the result is safe to append verbatim.
     *
     * @param stored the full transcript, ordered oldest-first
     * @param window the slice handed to the store by the chat memory
     */
    static List<ChatMessage> newMessages(List<ChatMessage> stored, List<ChatMessage> window) {
        if (window.isEmpty()) {
            return List.of();
        }
        if (stored.isEmpty()) {
            return List.copyOf(window);
        }

        // Step 1: the contiguous case, which is what every ordinary turn produces.
        // ensureCapacity only evicts from the front, so the window is a suffix of the
        // transcript followed by whatever is new.
        //
        // Taking the LARGEST overlap matters when the transcript repeats itself. For
        // stored=[u"hi", a"x", u"hi", a"x"] and window=[u"hi", a"x", u"hi"], the final
        // message is genuinely new; the largest overlap is 2, which appends it correctly.
        // (A smaller overlap would duplicate; pure subsequence matching -- step 2 -- would
        // match all three against the front of the transcript and silently drop it.)
        //
        // The residual risk is over-matching, which under-appends rather than duplicating,
        // and requires the last N messages of the transcript to all be equal to each other.
        int maxOverlap = Math.min(stored.size(), window.size());
        for (int len = maxOverlap; len >= 1; len--) {
            if (regionMatches(stored, stored.size() - len, window, len)) {
                return List.copyOf(window.subList(len, window.size()));
            }
        }

        // Step 2: no contiguous overlap. A SystemMessage is pinned and never evicted, so a
        // window can be an order-preserving subsequence of the transcript rather than a
        // suffix of it -- e.g. stored=[S,u1,a1,u2,a2] yields window=[S,u2,a2,u3].
        // Try progressively larger new tails, smallest first, so we append as little as
        // possible.
        for (int tail = 0; tail <= window.size(); tail++) {
            List<ChatMessage> head = window.subList(0, window.size() - tail);
            if (isSubsequence(head, stored)) {
                return List.copyOf(window.subList(window.size() - tail, window.size()));
            }
        }

        // Step 3: the window bears no relation to the transcript. Reachable via set() with
        // arbitrary content, or if another process wrote to this conversation since we read.
        // Appending is the safe choice -- losing messages is worse than duplicating them --
        // and the unique (conversation_id, seq) constraint is the real backstop.
        return List.copyOf(window);
    }

    /** True if {@code stored[from, from+len)} equals {@code window[0, len)}. */
    private static boolean regionMatches(
            List<ChatMessage> stored, int from, List<ChatMessage> window, int len) {
        for (int i = 0; i < len; i++) {
            if (!Objects.equals(stored.get(from + i), window.get(i))) {
                return false;
            }
        }
        return true;
    }

    /** True if every element of {@code candidate} appears in {@code within}, in order. */
    private static boolean isSubsequence(List<ChatMessage> candidate, List<ChatMessage> within) {
        int i = 0;
        for (ChatMessage message : within) {
            if (i == candidate.size()) {
                break;
            }
            if (Objects.equals(candidate.get(i), message)) {
                i++;
            }
        }
        return i == candidate.size();
    }
}

package com.enrola.chat;

import com.enrola.agent.Prompts;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * The agent's instructions: which version is live, what a conversation ran on, and how a new
 * version is made.
 *
 * <p>Editing never rewrites: every save appends a version and moves "current" to it, so a
 * conversation reviewed later can still be read against what the agent was actually told.
 */
@Service
public class PromptService {

    private static final Logger log = LoggerFactory.getLogger(PromptService.class);

    private final PromptRepository prompts;
    private final Clock clock;

    PromptService(PromptRepository prompts, Clock clock) {
        this.prompts = prompts;
        this.clock = clock;
    }

    /** The live version of each kind. */
    public Map<PromptKind, Prompt> currentSet() {
        Map<PromptKind, Prompt> set = new EnumMap<>(PromptKind.class);
        prompts.allCurrent().forEach(prompt -> set.put(prompt.kind(), prompt));
        return set;
    }

    /** The live instructions, composed the way the model receives them. */
    public Prompts currentPrompts() {
        return compose(currentSet());
    }

    /**
     * What a conversation ran on, composed the same way. Falls back to the live set for
     * conversations that opened before prompts were pinned -- an old transcript should still be
     * answerable, not an error.
     */
    public Prompts promptsFor(UUID conversationId) {
        List<Prompt> used = prompts.usedBy(conversationId);
        if (used.isEmpty()) {
            return currentPrompts();
        }
        Map<PromptKind, Prompt> set = new EnumMap<>(PromptKind.class);
        used.forEach(prompt -> set.put(prompt.kind(), prompt));
        // A kind added after this conversation opened has nothing pinned; take the live one
        // rather than sending the model an empty instruction.
        currentSet().forEach(set::putIfAbsent);
        return compose(set);
    }

    /** What a conversation ran on, for showing a reviewer. Empty before pinning existed. */
    public List<Prompt> usedBy(UUID conversationId) {
        return prompts.usedBy(conversationId);
    }

    /** Fixes the live versions onto a conversation, once, as it opens. */
    @Transactional
    public void pin(UUID conversationId) {
        List<UUID> ids = currentSet().values().stream().map(Prompt::id).toList();
        prompts.pin(conversationId, ids);
    }

    public List<Prompt> history(PromptKind kind) {
        return prompts.history(kind);
    }

    public Optional<Prompt> find(PromptKind kind, int version) {
        return prompts.find(kind, version);
    }

    public Optional<Prompt> current(PromptKind kind) {
        return prompts.current(kind);
    }

    /**
     * Saves {@code body} as a new current version of {@code kind}.
     *
     * <p>One transaction, and in this order: the partial unique index allows a single current
     * version per kind, so the old one has to step aside before the new one arrives. Editing an
     * old version is this same call -- the new version always goes on the end, which is what
     * makes reverting just another edit rather than a special case.
     */
    @Transactional
    public Prompt save(PromptKind kind, String body, String author) {
        if (!StringUtils.hasText(body)) {
            throw new IllegalArgumentException(kind.displayName() + " cannot be empty.");
        }
        int version = prompts.nextVersion(kind);
        prompts.demoteCurrent(kind);
        prompts.insert(kind, version, body.strip(), true, author, clock.instant());

        log.info("{} saved as v{} by {}", kind.displayName(), version, author);
        return prompts.find(kind, version).orElseThrow();
    }

    private static Prompts compose(Map<PromptKind, Prompt> set) {
        String brief = body(set, PromptKind.BRIEF);
        String faq = body(set, PromptKind.FAQ);
        // The brief's rules are meaningless without the facts they bound, so they travel
        // together as one system prompt -- the same composition the classpath version used.
        return new Prompts(brief + "\n\n" + faq, body(set, PromptKind.OUTREACH));
    }

    private static String body(Map<PromptKind, Prompt> set, PromptKind kind) {
        Prompt prompt = set.get(kind);
        return prompt == null ? "" : prompt.body();
    }

    /** The kinds with no version at all, so the seeder knows what to write. */
    List<PromptKind> unseeded() {
        List<PromptKind> missing = new ArrayList<>();
        for (PromptKind kind : PromptKind.values()) {
            if (!prompts.anyFor(kind)) {
                missing.add(kind);
            }
        }
        return missing;
    }

    /** Writes version 1 of a kind from the repo's copy. Only the seeder calls this. */
    @Transactional
    void seed(PromptKind kind, String body) {
        prompts.insert(kind, 1, body, true, null, clock.instant());
    }
}

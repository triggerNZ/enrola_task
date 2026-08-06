package com.enrola.chat;

import java.util.Locale;
import org.springframework.util.StringUtils;

/**
 * The instructions the agent runs on. One place that says what the prompts are, what each is
 * called on a page, and where its first version came from.
 *
 * <p>The brief and the FAQ are composed into the system prompt in that order; the outreach
 * instruction is the one-off ask that produces the first message.
 */
public enum PromptKind {
    BRIEF("Agent brief", "knowledge/agent-brief.md"),
    FAQ("Health insurance FAQ", "knowledge/health-insurance-faq.md"),
    OUTREACH("Outreach instruction", "knowledge/outreach-instruction.md");

    private final String displayName;
    private final String seedResource;

    PromptKind(String displayName, String seedResource) {
        this.displayName = displayName;
        this.seedResource = seedResource;
    }

    public String displayName() {
        return displayName;
    }

    /** Where version 1 comes from, the first time the application starts against a new database. */
    String seedResource() {
        return seedResource;
    }

    /** How it is stored and how it appears in a URL. */
    public String slug() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The kind named by {@code text}, or null if it names none. Never throws. */
    public static PromptKind parse(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return valueOf(text.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notAKind) {
            return null;
        }
    }
}

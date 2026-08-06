package com.enrola.agent;

import java.util.Locale;
import org.springframework.util.StringUtils;

/**
 * The four legislated hospital product tiers, in ascending order of what they must cover.
 *
 * <p>An enum rather than a string because it is a tool parameter: langchain4j turns the constants
 * into an enumerated JSON schema, so the model is told the four options rather than being trusted
 * to guess them.
 */
public enum Tier {
    BASIC,
    BRONZE,
    SILVER,
    GOLD;

    /** The tier named by {@code text}, or null if it names none. Never throws. */
    public static Tier parse(String text) {
        if (!StringUtils.hasText(text)) {
            return null;
        }
        try {
            return valueOf(text.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** True when this tier covers everything {@code other} does. */
    public boolean covers(Tier other) {
        return ordinal() >= other.ordinal();
    }

    /** "Silver", for reading back to someone. */
    public String display() {
        return name().charAt(0) + name().substring(1).toLowerCase(Locale.ROOT);
    }
}

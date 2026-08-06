package com.enrola.agent;

import java.math.BigDecimal;
import org.springframework.util.StringUtils;

/**
 * Who the agent is texting, as far as the agent needs to know. Deliberately not the lead
 * record: this package owns the type so it stays independent of where the details are kept.
 *
 * <p>A null {@code currentProvider} means no cover yet -- a first-timer rather than a switcher,
 * which is a different conversation. {@code monthlyPremium} is dollars per month.
 */
public record Recipient(
        String name, String state, String currentProvider, BigDecimal monthlyPremium) {

    /** The facts, rendered for the model. Absent details say so rather than going blank. */
    String describe() {
        return """
               Recipient:
               - Name: %s
               - State: %s
               - Currently with: %s
               - Paying: %s
               """
                .formatted(
                        or(name, "unknown"),
                        or(state, "unknown"),
                        or(currentProvider, "no cover yet"),
                        monthlyPremium == null
                                ? "n/a"
                                : "$" + monthlyPremium.stripTrailingZeros().toPlainString() + "/month");
    }

    private static String or(String value, String fallback) {
        return StringUtils.hasText(value) ? value : fallback;
    }
}

package com.enrola.chat;

import java.util.Set;
import org.springframework.util.StringUtils;

/**
 * The eight state and territory codes. Health insurance is priced and regulated by state, and
 * ambulance cover differs by it, so a typo here changes what the agent should say.
 *
 * <p>Checked at the edge rather than by a CHECK constraint, so a bulk import fails with a
 * message naming the value instead of a constraint violation.
 */
final class AustralianState {

    private static final Set<String> CODES =
            Set.of("NSW", "VIC", "QLD", "SA", "WA", "TAS", "NT", "ACT");

    private AustralianState() {}

    /** Uppercased and validated. Null or blank is allowed: the form may not have asked. */
    static String normalise(String state) {
        if (!StringUtils.hasText(state)) {
            return null;
        }
        String code = state.strip().toUpperCase();
        if (!CODES.contains(code)) {
            throw new IllegalArgumentException(
                    "Not an Australian state or territory: " + state + ". Expected one of " + CODES + ".");
        }
        return code;
    }
}

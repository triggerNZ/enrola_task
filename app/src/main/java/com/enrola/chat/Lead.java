package com.enrola.chat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Someone who was comparing health insurance and left their details.
 *
 * <p>Only {@code name} and {@code mobile} are certain: a form abandoned half way through is
 * exactly the lead worth texting. A null {@code currentProvider} means no cover yet, which is a
 * different conversation from switching. {@code currentPremium} is dollars per month.
 */
public record Lead(
        UUID id,
        String name,
        String mobile,
        String email,
        String state,
        String currentProvider,
        BigDecimal currentPremium,
        Instant consentAt,
        String status,
        Instant createdAt) {

    /** Statuses a lead moves through. One writer -- {@link LeadService} -- so they cannot drift. */
    public static final String NEW = "new";

    public static final String AWAITING_REPLY = "awaiting_reply";
    public static final String ENGAGED = "engaged";
    public static final String HANDED_OFF = "handed_off";
    public static final String OPTED_OUT = "opted_out";

    public boolean hasConsent() {
        return consentAt != null;
    }
}

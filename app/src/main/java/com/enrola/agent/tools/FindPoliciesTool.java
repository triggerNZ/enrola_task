package com.enrola.agent.tools;

import com.enrola.agent.ProductCatalogue;
import com.enrola.agent.Tier;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * Looks up real policies and their premiums. This is the only way the agent may state a price --
 * the brief forbids quoting one from memory, so an unanswerable price question has to become a
 * callback rather than a guess.
 */
@AgentTool
public class FindPoliciesTool {

    /** Enough to answer with, few enough to fit a text message. */
    private static final int LIMIT = 3;

    private final ProductCatalogue catalogue;

    public FindPoliciesTool(ProductCatalogue catalogue) {
        this.catalogue = catalogue;
    }

    @Tool(
            name = "find_policies",
            value =
                    "Look up policies and their monthly premiums. Use whenever price comes up, or they ask"
                + " what is available. Every filter is optional; results come back cheapest first."
                + " Quote only what this returns.")
    public String findPolicies(
            @P(value = "Only policies at this tier.", required = false) Tier tier,
            @P(value = "Only this fund, e.g. 'Bupa'.", required = false) String provider,
            // A string, not a number: models write "$250" and "250/month" as often as 250, and a
            // filter that throws on those would cost the turn. Parsed leniently below.
            @P(
                            name = "max_monthly_premium",
                            value = "Only policies at or under this many dollars a month.",
                            required = false)
                    String maxMonthlyPremium) {

        List<ProductCatalogue.Policy> found =
                catalogue.find(
                        tier == null ? null : tier.name(), provider, dollars(maxMonthlyPremium), LIMIT);

        if (found.isEmpty()) {
            return "Nothing matches. Available funds: "
                    + String.join(", ", catalogue.providers())
                    + ". Say nothing matched and offer a callback.";
        }

        StringJoiner out = new StringJoiner("; ");
        for (ProductCatalogue.Policy policy : found) {
            out.add(
                    "%s %s (%s) $%s/month, $%d excess"
                            .formatted(
                                    policy.provider(),
                                    policy.policy(),
                                    policy.tier().toLowerCase(Locale.ROOT),
                                    policy.monthlyPremium().stripTrailingZeros().toPlainString(),
                                    policy.hospitalExcess()));
        }
        return out
                + ". These are for a single, hospital only, NSW, with the full rebate. Give at most two,"
                + " and say the price depends on their details.";
    }

    /** Null for anything that is not a number, so a garbled filter is dropped rather than fatal. */
    private static BigDecimal dollars(String amount) {
        if (amount == null) {
            return null;
        }
        try {
            return new BigDecimal(amount.replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}

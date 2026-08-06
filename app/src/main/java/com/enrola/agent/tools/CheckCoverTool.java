package com.enrola.agent.tools;

import com.enrola.agent.ProductCatalogue;
import com.enrola.agent.Tier;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import java.util.List;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * Answers "is X covered?" from the legislated tier rules rather than from the model's memory.
 *
 * <p>Which tier covers what is law, not opinion, so getting it wrong is worse than most mistakes
 * an agent can make here: someone could buy Silver expecting a joint replacement.
 */
@AgentTool
public class CheckCoverTool {

    private final ProductCatalogue catalogue;

    public CheckCoverTool(ProductCatalogue catalogue) {
        this.catalogue = catalogue;
    }

    @Tool(
            name = "check_cover",
            value =
                    "Find the lowest tier that covers a treatment. Use for any 'is X covered' or 'does Silver"
                + " include X' question. Pass their own words for the treatment.")
    public String checkCover(
            @P("What they asked about, in their words: 'pregnancy', 'hip replacement', 'my back'.")
                    String treatment,
            @P(value = "The tier they are asking about, if they named one.", required = false)
                    Tier tier) {

        List<ProductCatalogue.Category> matches = catalogue.categoriesMatching(treatment);
        if (matches.isEmpty()) {
            return "No clinical category matches \"%s\". Say you are not sure and offer a callback."
                    .formatted(treatment);
        }

        StringJoiner out = new StringJoiner("; ");
        for (ProductCatalogue.Category category : matches) {
            out.add(describe(category, tier));
        }
        return out.toString();
    }

    private String describe(ProductCatalogue.Category category, Tier asked) {
        Tier minimum = Tier.parse(category.minimumTier());
        String minimumName = minimum == null ? category.minimumTier() : minimum.display();

        if (asked == null) {
            return "%s: covered from %s up".formatted(category.name(), minimumName);
        }
        String askedName = asked.display().toLowerCase(Locale.ROOT);
        return minimum != null && asked.covers(minimum)
                ? "%s: yes, %s covers it".formatted(category.name(), askedName)
                : "%s: no, %s does not cover it -- you need %s"
                        .formatted(category.name(), askedName, minimumName);
    }
}

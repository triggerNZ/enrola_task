package com.enrola.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;

import com.enrola.agent.ProductCatalogue;
import com.enrola.agent.Tier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * What the tools hand back to the model. These strings become messages in the transcript and get
 * paraphrased into a text, so they are asserted for content.
 *
 * <p>Called as ordinary methods: the arguments are typed now that langchain4j binds them, so
 * these no longer build JSON by hand. {@link ToolRegistryTest} covers the binding itself.
 */
class CatalogueToolsTest {

    private final ProductCatalogue catalogue = new ProductCatalogue("knowledge/products.json");
    private final FindPoliciesTool find = new FindPoliciesTool(catalogue);
    private final CheckCoverTool check = new CheckCoverTool(catalogue);

    @Nested
    @DisplayName("find_policies")
    class FindPolicies {

        @Test
        void quotesProviderPolicyPriceAndExcess() {
            String result = find.findPolicies(Tier.GOLD, null, null);

            assertThat(result).contains("$").contains("/month").contains("excess").contains("gold");
        }

        @Test
        @DisplayName("says what the price assumes, so the agent can pass that on")
        void statesItsAssumptions() {
            assertThat(find.findPolicies(null, null, null))
                    .contains("single")
                    .contains("NSW")
                    .contains("rebate");
        }

        @Test
        void honoursAPriceCeiling() {
            assertThat(find.findPolicies(null, null, "90")).contains("84.5").doesNotContain("341.05");
        }

        @Test
        @DisplayName("a price written the way a model writes it still filters")
        void toleratesAPriceAsText() {
            assertThat(find.findPolicies(null, null, "$90")).contains("84.5").doesNotContain("341.05");
            assertThat(find.findPolicies(null, null, "90/month")).contains("84.5");
        }

        @Test
        @DisplayName("an unparseable ceiling drops the filter rather than failing the turn")
        void nonsensePriceIsIgnored() {
            assertThat(find.findPolicies(null, null, "cheap")).contains("$");
        }

        @Test
        @DisplayName("nothing matching tells the agent to say so, not to invent one")
        void nothingMatchingIsSaidPlainly() {
            String result = find.findPolicies(null, "Nonexistent Fund", null);

            assertThat(result).contains("Nothing matches").contains("callback");
        }

        @Test
        void noFiltersReturnsTheCheapest() {
            assertThat(find.findPolicies(null, null, null)).contains("$");
        }
    }

    @Nested
    @DisplayName("check_cover")
    class CheckCover {

        @Test
        @DisplayName("names the lowest tier that covers it")
        void reportsTheMinimumTier() {
            assertThat(check.checkCover("pregnancy", null))
                    .contains("Pregnancy and birth")
                    .contains("Gold");
        }

        @Test
        @DisplayName("answers a tier question with yes or no, and what they would need")
        void answersAboutASpecificTier() {
            assertThat(check.checkCover("joint replacement", Tier.SILVER)).contains("no").contains("Gold");
            assertThat(check.checkCover("cataracts", Tier.SILVER)).contains("yes");
            assertThat(check.checkCover("cataracts", Tier.BRONZE)).contains("no").contains("Silver");
        }

        @Test
        @DisplayName("something with no category says so rather than guessing")
        void unknownTreatmentIsAdmitted() {
            assertThat(check.checkCover("time travel", null)).contains("not sure").contains("callback");
        }

        @Test
        void missingTreatmentDoesNotThrow() {
            assertThat(check.checkCover(null, null)).contains("callback");
        }
    }
}

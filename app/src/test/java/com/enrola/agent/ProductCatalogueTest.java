package com.enrola.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The real {@code products.json} on the classpath, because the point of these is that the file
 * the agent quotes from is well formed and internally consistent. A fixture would prove nothing:
 * a typo in the shipped file is exactly the failure worth catching.
 */
class ProductCatalogueTest {

    private final ProductCatalogue catalogue = new ProductCatalogue("knowledge/products.json");

    @Test
    void loadsTheShippedCatalogue() {
        assertThat(catalogue.tiers()).extracting(ProductCatalogue.TierInfo::code)
                .containsExactly("BASIC", "BRONZE", "SILVER", "GOLD");
        assertThat(catalogue.providers()).contains("Bupa", "HCF", "Medibank", "nib", "HBF");
        assertThat(catalogue.find(null, null, null, 100)).isNotEmpty();
    }

    @Test
    @DisplayName("a missing catalogue stops startup rather than answering prices from nothing")
    void refusesToStartWithoutTheFile() {
        assertThatThrownBy(() -> new ProductCatalogue("knowledge/not-here.json"))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining("not-here.json");
    }

    @Test
    void findsCheapestFirst() {
        List<ProductCatalogue.Policy> found = catalogue.find(null, null, null, 3);

        assertThat(found).hasSize(3);
        assertThat(found)
                .extracting(ProductCatalogue.Policy::monthlyPremium)
                .isSorted();
    }

    @Test
    void filtersByTierProviderAndPrice() {
        assertThat(catalogue.find("GOLD", null, null, 10))
                .isNotEmpty()
                .allSatisfy(p -> assertThat(p.tier()).isEqualTo("GOLD"));
        assertThat(catalogue.find(null, "bupa", null, 10))
                .isNotEmpty()
                .allSatisfy(p -> assertThat(p.provider()).isEqualTo("Bupa"));
        assertThat(catalogue.find(null, null, new BigDecimal("110"), 10))
                .isNotEmpty()
                .allSatisfy(p -> assertThat(p.monthlyPremium()).isLessThanOrEqualTo(new BigDecimal("110")));
    }

    @Test
    @DisplayName("an unknown tier or provider matches nothing rather than everything")
    void unknownFiltersDoNotSilentlyMatchAll() {
        assertThat(catalogue.find(null, "NotAFund", null, 10)).isEmpty();
        // A tier the file does not define is dropped, so the filter is simply not applied --
        // the tool turns an empty result into "nothing matched", never a wrong quote.
        assertThat(ProductCatalogue.normaliseTier("PLATINUM")).isNull();
    }

    @Test
    @DisplayName("categories are found by the words people actually use")
    void matchesCategoriesLoosely() {
        assertThat(catalogue.categoriesMatching("pregnancy"))
                .extracting(ProductCatalogue.Category::name)
                .contains("Pregnancy and birth");
        assertThat(catalogue.categoriesMatching("joint replacement"))
                .extracting(ProductCatalogue.Category::name)
                .contains("Joint replacements");
        assertThat(catalogue.categoriesMatching("my back"))
                .extracting(ProductCatalogue.Category::name)
                .contains("Back, neck and spine");
        assertThat(catalogue.categoriesMatching("astrology")).isEmpty();
        assertThat(catalogue.categoriesMatching(null)).isEmpty();
    }

    @Test
    @DisplayName("the file's tier exclusions agree with its own category list")
    void exclusionsAgreeWithCategories() {
        List<String> order = List.of("BASIC", "BRONZE", "SILVER", "GOLD");
        assertThat(catalogue.categories()).hasSize(38);

        for (ProductCatalogue.TierInfo tier : catalogue.tiers()) {
            List<String> shouldExclude =
                    catalogue.categories().stream()
                            .filter(c -> order.indexOf(c.minimumTier()) > order.indexOf(tier.code()))
                            .map(ProductCatalogue.Category::name)
                            .toList();

            // If these ever disagree, the agent tells someone a treatment is covered when it
            // is not -- which is the one mistake in this file that actually hurts.
            assertThat(tier.excludedCategories())
                    .as("%s exclusions", tier.code())
                    .containsExactlyInAnyOrderElementsOf(shouldExclude);
            assertThat(tier.categoriesCovered())
                    .as("%s covered count", tier.code())
                    .isEqualTo(38 - shouldExclude.size());
        }
    }

    @Test
    @DisplayName("every policy sits at a tier the file defines")
    void policiesReferenceRealTiers() {
        List<String> known = catalogue.tiers().stream().map(ProductCatalogue.TierInfo::code).toList();

        assertThat(catalogue.find(null, null, null, 100))
                .allSatisfy(p -> assertThat(known).contains(p.tier()));
    }
}

package com.enrola.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * The policies, tiers and exclusions the agent can quote, read from {@code products.json} at
 * startup.
 *
 * <p>Loaded once and held in memory: it is a few kilobytes, it changes when someone edits the
 * file rather than at runtime, and a lookup happening mid-conversation should not touch a disk.
 */
@Component
public class ProductCatalogue {

    private static final Logger log = LoggerFactory.getLogger(ProductCatalogue.class);

    /** Ascending, so "cheapest" and "one tier up" both mean something. */
    private static final List<String> TIER_ORDER = List.of("BASIC", "BRONZE", "SILVER", "GOLD");

    private final List<Policy> policies;
    private final List<TierInfo> tiers;
    private final List<Category> categories;

    public ProductCatalogue(@Value("${catalogue.path:knowledge/products.json}") String path) {
        JsonNode root = read(path);
        this.tiers = readTiers(root);
        this.categories = readCategories(root);
        this.policies = readPolicies(root);

        String warning = root.path("illustrativeProducts").path("WARNING").asText("");
        if (StringUtils.hasText(warning)) {
            // Loud on purpose. These premiums reach customers now, and the file itself says
            // they are invented -- that has to be visible in the log, not only in the JSON.
            log.warn("Catalogue {}: {} policies loaded. {}", path, policies.size(), warning);
        } else {
            log.info("Catalogue {}: {} policies, {} tiers.", path, policies.size(), tiers.size());
        }
    }

    /** Cheapest first. Any argument may be null, meaning "no filter on this". */
    public List<Policy> find(String tier, String provider, BigDecimal maxMonthlyPremium, int limit) {
        String wantedTier = normaliseTier(tier);
        return policies.stream()
                .filter(p -> wantedTier == null || p.tier().equals(wantedTier))
                .filter(p -> provider == null || p.provider().equalsIgnoreCase(provider.strip()))
                .filter(p -> maxMonthlyPremium == null || p.monthlyPremium().compareTo(maxMonthlyPremium) <= 0)
                .sorted(Comparator.comparing(Policy::monthlyPremium))
                .limit(Math.max(1, limit))
                .toList();
    }

    /**
     * The clinical categories matching what someone actually typed -- "pregnancy", "hip
     * replacement", "my back". Substring both ways, because the official names are phrases
     * ("Back, neck and spine") that nobody says out loud.
     */
    public List<Category> categoriesMatching(String query) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        String needle = query.strip().toLowerCase(Locale.ROOT);
        List<Category> exact =
                categories.stream().filter(c -> c.name().toLowerCase(Locale.ROOT).equals(needle)).toList();
        if (!exact.isEmpty()) {
            return exact;
        }
        return categories.stream()
                .filter(c -> matches(c.name().toLowerCase(Locale.ROOT), needle))
                .limit(4)
                .toList();
    }

    public Optional<TierInfo> tier(String code) {
        String wanted = normaliseTier(code);
        return tiers.stream().filter(t -> t.code().equals(wanted)).findFirst();
    }

    public List<TierInfo> tiers() {
        return tiers;
    }

    public List<Category> categories() {
        return categories;
    }

    public List<String> providers() {
        return policies.stream().map(Policy::provider).distinct().sorted().toList();
    }

    private static boolean matches(String name, String needle) {
        if (name.contains(needle)) {
            return true;
        }
        // The other direction, so "pregnancy cover" finds "Pregnancy and birth".
        for (String word : needle.split("[^a-z]+")) {
            if (word.length() > 3 && name.contains(word)) {
                return true;
            }
        }
        return false;
    }

    public static String normaliseTier(String tier) {
        if (!StringUtils.hasText(tier)) {
            return null;
        }
        String code = tier.strip().toUpperCase(Locale.ROOT);
        return TIER_ORDER.contains(code) ? code : null;
    }

    private static List<TierInfo> readTiers(JsonNode root) {
        JsonNode exclusions = root.path("sourced").path("exclusionsByTier");
        List<TierInfo> read = new ArrayList<>();
        for (JsonNode node : root.path("sourced").path("tiers")) {
            String code = node.path("code").asText();
            read.add(
                    new TierInfo(
                            code,
                            node.path("name").asText(),
                            node.path("categoriesCovered").asInt(),
                            node.path("summary").asText(),
                            decimal(node.path("indicativeMonthlyPremiumSingle").path("average")),
                            decimal(node.path("indicativeMonthlyPremiumSingle").path("lowest")),
                            strings(exclusions.path(code))));
        }
        return List.copyOf(read);
    }

    private static List<Category> readCategories(JsonNode root) {
        List<Category> read = new ArrayList<>();
        for (JsonNode node : root.path("sourced").path("clinicalCategories")) {
            read.add(new Category(node.path("name").asText(), node.path("minimumTier").asText()));
        }
        return List.copyOf(read);
    }

    private static List<Policy> readPolicies(JsonNode root) {
        List<Policy> read = new ArrayList<>();
        for (JsonNode node : root.path("illustrativeProducts").path("products")) {
            read.add(
                    new Policy(
                            node.path("id").asText(),
                            node.path("provider").asText(),
                            node.path("policy").asText(),
                            node.path("tier").asText(),
                            decimal(node.path("monthlyPremium")),
                            node.path("hospitalExcess").asInt(),
                            strings(node.path("aboveTierMinimum")),
                            strings(node.path("productExclusions"))));
        }
        return List.copyOf(read);
    }

    private static List<String> strings(JsonNode array) {
        List<String> read = new ArrayList<>();
        array.forEach(node -> read.add(node.asText()));
        return List.copyOf(read);
    }

    private static BigDecimal decimal(JsonNode node) {
        return node.isMissingNode() || node.isNull() ? null : node.decimalValue();
    }

    private static JsonNode read(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return new ObjectMapper().readTree(in);
        } catch (IOException e) {
            // The agent can quote prices now, so starting without the catalogue would mean
            // answering price questions from nothing at all. Refuse to start instead.
            throw new UncheckedIOException("Cannot read the product catalogue at " + path + ".", e);
        }
    }

    /** One policy a customer could buy. */
    public record Policy(
            String id,
            String provider,
            String policy,
            String tier,
            BigDecimal monthlyPremium,
            int hospitalExcess,
            List<String> aboveTierMinimum,
            List<String> productExclusions) {}

    /** A product tier, and what it is not required to cover. */
    public record TierInfo(
            String code,
            String name,
            int categoriesCovered,
            String summary,
            BigDecimal averagePremium,
            BigDecimal lowestPremium,
            List<String> excludedCategories) {}

    /** A clinical category, and the lowest tier that must cover it. */
    public record Category(String name, String minimumTier) {}
}

package com.enrola.agent.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enrola.agent.BookingOutcome;
import com.enrola.agent.CallbackTool;
import com.enrola.agent.ProductCatalogue;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonEnumSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What langchain4j derives from the annotations, and what it does with the model's arguments.
 *
 * <p>This is the substitution these tools depend on: the schema the model is shown and the
 * binding onto the method parameters are both generated, so nothing else asserts that they are
 * right. Previously both were written out by hand here.
 */
class ToolRegistryTest {

    private static final ZoneId SYDNEY = ZoneId.of("Australia/Sydney");

    private final RecordingCallbacks callbacks = new RecordingCallbacks();
    private final ProductCatalogue catalogue = new ProductCatalogue("knowledge/products.json");
    private final ToolRegistry registry =
            ToolRegistry.of(
                    new ArrangeCallbackTool(callbacks, SYDNEY),
                    new FindPoliciesTool(catalogue),
                    new CheckCoverTool(catalogue));

    private static class RecordingCallbacks implements CallbackTool {
        Object memoryId;
        Instant startsAt;
        String theirWords;
        String topic;
        LocalDate fromDate;
        int calls;

        @Override
        public BookingOutcome arrangeCallback(
                Object memoryId, Instant startsAt, String theirWords, String topic) {
            this.memoryId = memoryId;
            this.startsAt = startsAt;
            this.theirWords = theirWords;
            this.topic = topic;
            this.calls++;
            return new BookingOutcome.Booked(startsAt.atZone(SYDNEY));
        }

        @Override
        public BookingOutcome.Unavailable nextAvailable(LocalDate from, int count) {
            this.fromDate = from;
            return new BookingOutcome.Unavailable("", List.of());
        }
    }

    private static ToolExecutionRequest call(String name, String arguments) {
        return ToolExecutionRequest.builder().id("c1").name(name).arguments(arguments).build();
    }

    private ToolSpecification specification(String name) {
        return registry.specifications().stream()
                .filter(s -> s.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No tool named " + name));
    }

    @Test
    @DisplayName("the names are pinned, because the brief refers to them by name")
    void toolNamesAreStable() {
        assertThat(registry.specifications())
                .extracting(ToolSpecification::name)
                .containsExactlyInAnyOrder("arrange_callback", "find_policies", "check_cover");
    }

    @Test
    @DisplayName("descriptions come from the annotation, so they cannot drift from the code")
    void descriptionsAreGenerated() {
        assertThat(specification("find_policies").description())
                .contains("monthly premiums")
                .contains("cheapest first");
    }

    @Test
    @DisplayName("a Tier parameter becomes an enumerated schema, not a free string")
    void enumParametersConstrainTheModel() {
        JsonObjectSchema parameters = specification("check_cover").parameters();

        assertThat(parameters.properties().get("tier"))
                .isInstanceOfSatisfying(
                        JsonEnumSchema.class,
                        schema ->
                                assertThat(schema.enumValues())
                                        .containsExactly("BASIC", "BRONZE", "SILVER", "GOLD"));
        // Optional stays optional, required stays required.
        assertThat(parameters.required()).containsExactly("treatment");
    }

    @Test
    @DisplayName("the memory id is bound but never shown to the model")
    void memoryIdIsHiddenFromTheSchema() {
        UUID conversationId = UUID.randomUUID();
        JsonObjectSchema parameters = specification("arrange_callback").parameters();

        assertThat(parameters.properties())
                .containsOnlyKeys("starts_at", "from_date", "their_words", "topic");

        registry
                .find("arrange_callback")
                .orElseThrow()
                .execute(
                        call(
                                "arrange_callback",
                                "{\"starts_at\":\"2026-08-06T14:15\",\"their_words\":\"tomorrow arvo\","
                                        + "\"topic\":\"price\"}"),
                        conversationId);

        assertThat(callbacks.calls).isEqualTo(1);
        assertThat(callbacks.memoryId).isEqualTo(conversationId);
        assertThat(callbacks.startsAt)
                .isEqualTo(LocalDateTime.parse("2026-08-06T14:15").atZone(SYDNEY).toInstant());
        assertThat(callbacks.theirWords).isEqualTo("tomorrow arvo");
        assertThat(callbacks.topic).isEqualTo("price");
    }

    @Test
    @DisplayName("a date without a time is passed to availability rather than treated as a booking")
    void fromDateIsBound() {
        String result =
                registry
                        .find("arrange_callback")
                        .orElseThrow()
                        .execute(
                                call("arrange_callback", "{\"from_date\":\"2026-08-10\"}"),
                                UUID.randomUUID());

        assertThat(callbacks.fromDate).isEqualTo(LocalDate.parse("2026-08-10"));
        assertThat(callbacks.calls).isZero();
        assertThat(result).contains("Nothing is free");
    }

    @Test
    @DisplayName("an omitted optional argument arrives as null, not as a failure")
    void optionalArgumentsMayBeAbsent() {
        String result =
                registry
                        .find("arrange_callback")
                        .orElseThrow()
                        .execute(
                                call("arrange_callback", "{\"starts_at\":\"2026-08-06T14:15\"}"),
                                UUID.randomUUID());

        assertThat(result).startsWith("Booked:");
        assertThat(callbacks.topic).isNull();
        assertThat(callbacks.theirWords).isNull();
    }

    @Test
    @DisplayName("the model's string for an enum is bound to the constant")
    void enumArgumentsAreBound() {
        String result =
                registry
                        .find("check_cover")
                        .orElseThrow()
                        .execute(
                                call("check_cover", "{\"treatment\":\"joint replacement\",\"tier\":\"SILVER\"}"),
                                UUID.randomUUID());

        assertThat(result).contains("silver does not cover it").contains("Gold");
    }

    @Test
    @DisplayName("no arguments at all is a call with everything null")
    void emptyArgumentsAreTolerated() {
        String result =
                registry.find("find_policies").orElseThrow().execute(call("find_policies", "{}"), null);

        assertThat(result).contains("$");
    }

    @Test
    void unknownToolsAreNotFound() {
        assertThat(registry.find("teleport")).isEmpty();
    }

    @Test
    @DisplayName("two tools with one name is a startup failure, not a silent overwrite")
    void duplicateNamesAreRejected() {
        assertThatThrownBy(
                        () -> ToolRegistry.of(new CheckCoverTool(catalogue), new CheckCoverTool(catalogue)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("check_cover");
    }

    @Test
    void everyToolIsFindable() {
        assertThat(registry.specifications()).hasSize(3);
        for (ToolSpecification specification : List.copyOf(registry.specifications())) {
            assertThat(registry.find(specification.name())).isPresent();
        }
    }
}

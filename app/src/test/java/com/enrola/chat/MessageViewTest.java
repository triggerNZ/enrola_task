package com.enrola.chat;

import static org.assertj.core.api.Assertions.assertThat;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Flattening a transcript message for a reviewer. Pure: no Spring, no database.
 *
 * <p>The tool cases carry the weight. A conversation where the agent quoted a premium is only
 * reviewable if the page can show which tool was asked, what it was passed, and what it said.
 */
class MessageViewTest {

    private static ToolExecutionRequest request(String name, String arguments) {
        return ToolExecutionRequest.builder().id("c1").name(name).arguments(arguments).build();
    }

    @Test
    void plainMessagesAreJustText() {
        assertThat(MessageView.of(UserMessage.from("hello")))
                .isEqualTo(new MessageView("USER", "hello"));
        assertThat(MessageView.of(AiMessage.from("hi there")))
                .isEqualTo(new MessageView("AI", "hi there"));
        assertThat(MessageView.of(SystemMessage.from("be terse")))
                .isEqualTo(new MessageView("SYSTEM", "be terse"));
    }

    @Test
    @DisplayName("a tool call carries the name and every argument, split out of the JSON")
    void toolCallsCarryTheirArguments() {
        MessageView view =
                MessageView.of(
                        AiMessage.from(
                                request(
                                        "find_policies",
                                        "{\"tier\":\"GOLD\",\"max_monthly_premium\":\"250\"}")));

        assertThat(view.type()).isEqualTo("AI");
        assertThat(view.hasToolCalls()).isTrue();
        assertThat(view.toolCalls())
                .singleElement()
                .satisfies(
                        call -> {
                            assertThat(call.name()).isEqualTo("find_policies");
                            assertThat(call.arguments())
                                    .containsExactly(
                                            new MessageView.Argument("tier", "GOLD"),
                                            new MessageView.Argument("max_monthly_premium", "250"));
                        });
    }

    @Test
    @DisplayName("numbers and booleans read as themselves, not as quoted JSON")
    void scalarArgumentsAreUnquoted() {
        MessageView view =
                MessageView.of(
                        AiMessage.from(request("find_policies", "{\"limit\":3,\"cheapest\":true}")));

        assertThat(view.toolCalls().get(0).arguments())
                .containsExactly(
                        new MessageView.Argument("limit", "3"),
                        new MessageView.Argument("cheapest", "true"));
    }

    @Test
    @DisplayName("a nested argument is shown as the JSON it was, rather than dropped")
    void nestedArgumentsSurvive() {
        MessageView view =
                MessageView.of(AiMessage.from(request("x", "{\"filters\":{\"tier\":\"GOLD\"}}")));

        assertThat(view.toolCalls().get(0).arguments())
                .containsExactly(new MessageView.Argument("filters", "{\"tier\":\"GOLD\"}"));
    }

    @Test
    @DisplayName("a model that sent nonsense has that shown, not swallowed")
    void unparseableArgumentsAreShownRaw() {
        MessageView view = MessageView.of(AiMessage.from(request("check_cover", "not json")));

        assertThat(view.toolCalls().get(0).arguments())
                .containsExactly(new MessageView.Argument("arguments", "not json"));
    }

    @Test
    void aCallWithNoArgumentsIsNotAnError() {
        assertThat(MessageView.of(AiMessage.from(request("arrange_callback", "{}"))).toolCalls())
                .singleElement()
                .satisfies(call -> assertThat(call.arguments()).isEmpty());
    }

    @Test
    @DisplayName("a message can both say something and ask for a tool")
    void textAndToolCallsTogether() {
        MessageView view =
                MessageView.of(
                        AiMessage.from("Let me check.", List.of(request("check_cover", "{\"x\":1}"))));

        assertThat(view.text()).isEqualTo("Let me check.");
        assertThat(view.hasToolCalls()).isTrue();
    }

    @Test
    @DisplayName("a result says which tool produced it, alongside what it returned")
    void resultsCarryTheirToolName() {
        MessageView view =
                MessageView.of(
                        ToolExecutionResultMessage.from(
                                request("find_policies", "{}"), "Medibank Starter Basic $84.50/month"));

        assertThat(view.type()).isEqualTo("TOOL_EXECUTION_RESULT");
        assertThat(view.toolName()).isEqualTo("find_policies");
        assertThat(view.text()).isEqualTo("Medibank Starter Basic $84.50/month");
        assertThat(view.isToolResult()).isTrue();
        assertThat(view.hasToolCalls()).isFalse();
    }

    @Test
    @DisplayName("an ordinary message is neither a call nor a result")
    void prosaicMessagesAreNeither() {
        MessageView view = MessageView.of(UserMessage.from("hello"));

        assertThat(view.hasToolCalls()).isFalse();
        assertThat(view.isToolResult()).isFalse();
    }
}

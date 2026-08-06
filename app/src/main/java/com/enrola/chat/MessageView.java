package com.enrola.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import java.util.ArrayList;
import java.util.List;

/**
 * One transcript message, flattened for JSON: {@code type} is langchain4j's message type
 * ({@code USER}, {@code AI}, {@code SYSTEM}, {@code TOOL_EXECUTION_RESULT}).
 *
 * <p>A turn where the agent looked something up is three messages, not one: the model's request,
 * the result it was handed, and the answer it then wrote. All three are carried here, because
 * reviewing a conversation means being able to see that the agent quoted $84.50 because
 * {@code find_policies} said so, and not because it made it up.
 *
 * @param text the prose, or null where a message carries none -- an {@link AiMessage} that only
 *     asks for tools has no text of its own
 * @param toolCalls what the model asked to run; empty on everything but such a message
 * @param toolName which tool produced this result; null on everything but a result message
 */
public record MessageView(String type, String text, List<ToolCall> toolCalls, String toolName) {

    /** A tool the model asked to run, and what it passed. */
    public record ToolCall(String name, List<Argument> arguments) {}

    /** One argument, already pulled out of the JSON so a template does not have to parse it. */
    public record Argument(String name, String value) {}

    private static final ObjectMapper JSON = new ObjectMapper();

    /** For the ordinary case: a message that is only words. */
    public MessageView(String type, String text) {
        this(type, text, List.of(), null);
    }

    public static MessageView of(ChatMessage message) {
        return switch (message) {
            case UserMessage user ->
                    new MessageView(type(message), user.hasSingleText() ? user.singleText() : null);
            case SystemMessage system -> new MessageView(type(message), system.text());
            case ToolExecutionResultMessage result ->
                    new MessageView(type(message), result.text(), List.of(), result.toolName());
            case AiMessage ai ->
                    new MessageView(type(message), ai.text(), toolCalls(ai), null);
            default -> new MessageView(type(message), null);
        };
    }

    public boolean hasToolCalls() {
        return toolCalls != null && !toolCalls.isEmpty();
    }

    public boolean isToolResult() {
        return toolName != null;
    }

    private static String type(ChatMessage message) {
        return message.type().name();
    }

    private static List<ToolCall> toolCalls(AiMessage message) {
        if (!message.hasToolExecutionRequests()) {
            return List.of();
        }
        List<ToolCall> calls = new ArrayList<>();
        for (ToolExecutionRequest request : message.toolExecutionRequests()) {
            calls.add(new ToolCall(request.name(), arguments(request.arguments())));
        }
        return List.copyOf(calls);
    }

    /**
     * The arguments as name and value pairs. Parsed here rather than in a template, and a blob
     * that will not parse is shown as it arrived -- a review page should show what the model
     * actually sent, including when it sent nonsense.
     */
    private static List<Argument> arguments(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<Argument> arguments = new ArrayList<>();
        try {
            JsonNode parsed = JSON.readTree(json);
            parsed.properties()
                    .forEach(
                            field ->
                                    arguments.add(
                                            new Argument(
                                                    field.getKey(),
                                                    field.getValue().isValueNode()
                                                            ? field.getValue().asText()
                                                            : field.getValue().toString())));
        } catch (Exception unparseable) {
            arguments.add(new Argument("arguments", json));
        }
        return List.copyOf(arguments);
    }
}

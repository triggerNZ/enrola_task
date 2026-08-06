package com.enrola.agent;


import com.enrola.agent.tools.ToolRegistry;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * How the application talks to the model: what history a call carries, what standing
 * instructions go with it, and what is committed once the model has answered.
 *
 * <p>This is the seam for agent work -- prompts, tools, multi-step workflow. It knows nothing
 * about conversations as records, HTTP, or SQL; it is handed a memory id and works through
 * langchain4j's {@link ChatMemoryStore}, whichever implementation is wired in.
 */
@Component
public class ChatAgent {

    private static final Logger log = LoggerFactory.getLogger(ChatAgent.class);

    /**
     * A turn may take several calls: look a price up, check what a tier covers, then answer.
     * Five leaves room for two lookups and the brevity retry; past that the model is looping,
     * and a bound is cheaper than discovering that on a bill.
     */
    private static final int MAX_MODEL_CALLS_PER_TURN = 5;

    /** The opening is one SMS, whatever {@code sms.max-parts} allows the rest of the conversation. */
    private static final int OPENING_PARTS = 1;

    /** Required by law on a cold message, so it is appended here rather than left to the model. */
    private static final String OPT_OUT_FOOTER = "\nReply 'stop' to opt out";

    /** "Thu 7 Aug 2026, 8:42am AEST" -- enough for the model to resolve "tomorrow" and "arvo". */
    private static final DateTimeFormatter NOW =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy, h:mma z", java.util.Locale.ENGLISH);

    private final ChatModel chatModel;
    private final ChatMemoryStore memoryStore;
    private final ToolRegistry tools;
    private final int maxMessages;
    private final int maxParts;
    private final String agentName;
    private final String company;
    private final Clock clock;

    ChatAgent(
            ChatModel chatModel,
            ChatMemoryStore memoryStore,
            ToolRegistry tools,
            @Value("${chat.max-messages}") int maxMessages,
            @Value("${sms.max-parts}") int maxParts,
            @Value("${agent.name}") String agentName,
            @Value("${agent.company}") String company,
            Clock clock) {
        if (maxMessages < 2) {
            // A window of one cannot hold a question and its answer, and it also leaves the
            // store unable to tell a new message from one it has already seen.
            throw new IllegalStateException("chat.max-messages must be at least 2, got " + maxMessages);
        }
        if (maxParts < 1) {
            throw new IllegalStateException("sms.max-parts must be at least 1, got " + maxParts);
        }
        this.chatModel = chatModel;
        this.memoryStore = memoryStore;
        this.tools = tools;
        this.maxMessages = maxMessages;
        this.maxParts = maxParts;
        this.agentName = agentName;
        this.company = company;
        this.clock = clock;
    }

    /**
     * The first message, written before they have said anything. Only the reply is committed, so
     * the transcript starts with the agent speaking -- the instruction that prompted it is
     * scaffolding, not part of the conversation.
     *
     * <p>Always exactly one SMS -- not for cost, since a handset reassembles a concatenated
     * message invisibly, but because a 136-character budget is what makes the opening as short
     * as a cold text has to be. Later messages answer a question they asked, so they get the
     * fuller {@code sms.max-parts} budget.
     */
    public Reply open(Object memoryId, Recipient recipient, Prompts prompts) {
        ChatMemory memory = memoryFor(memoryId);
        int budget = MessageSegmenter.budget(OPENING_PARTS) - OPT_OUT_FOOTER.length();
        List<ChatMessage> request =
                List.of(systemMessage(recipient, budget, prompts), UserMessage.from(prompts.outreach()));

        String opening = call(request, false).text();
        // Measured in segments, not characters: an emoji anywhere in the message drops a single
        // SMS from 160 units to 70, so a character count against the GSM budget would pass
        // something that actually needs three messages.
        if (MessageSegmenter.segments(withFooter(opening)) > OPENING_PARTS) {
            opening = askForShorter(request, opening, budget);
        }

        Reply reply = Reply.of(withFooter(fitToOneMessage(opening)));

        memory.add(AiMessage.from(reply.text()));
        return reply;
    }

    /**
     * The footer is appended rather than written by the model: it is the one part of this message
     * the law requires, so it must not depend on the model remembering it, and must not be what
     * the trimmer removes when the rest runs long.
     */
    private static String withFooter(String body) {
        return body + OPT_OUT_FOOTER;
    }

    /** Cuts the body down until it plus the footer is one message, in the body's own encoding. */
    private static String fitToOneMessage(String body) {
        int capacity =
                MessageSegmenter.singleCapacity(withFooter(body))
                        - MessageSegmenter.length(OPT_OUT_FOOTER);
        return MessageSegmenter.trimToLength(body, capacity);
    }

    /**
     * One exchange: the model answers {@code text} in the light of everything remembered under
     * {@code memoryId}, and both sides of the turn are committed to the store.
     *
     * @throws LlmUnavailableException if the model call fails; nothing is committed in that case
     */
    public Reply respondTo(Object memoryId, String text, Recipient recipient, Prompts prompts) {
        ChatMemory memory = memoryFor(memoryId);
        UserMessage user = UserMessage.from(text);

        // Build the request without committing the user message first. The store is
        // append-only, so a message added before a failed call could not be taken back --
        // ChatMemory has no remove, and set(listWithoutLast) is a no-op because the
        // reconciler correctly finds nothing new to append. Deferring the write keeps the
        // transcript a clean user/assistant alternation, and halves the store round-trips.
        List<ChatMessage> request = new ArrayList<>();
        request.add(systemMessage(recipient, MessageSegmenter.budget(maxParts), prompts));
        request.addAll(memory.messages());
        request.add(user);

        // Everything the turn produced, held back until the model settles on an answer. A tool
        // round trip has to reach the transcript -- the model needs to see its own call and the
        // result on the next turn -- but only once the turn as a whole has succeeded.
        List<ChatMessage> pending = new ArrayList<>();
        pending.add(user);

        for (int callsLeft = MAX_MODEL_CALLS_PER_TURN; callsLeft > 0; callsLeft--) {
            AiMessage answer = call(request, true);

            if (!answer.hasToolExecutionRequests()) {
                Reply reply = shorten(answer.text(), request);
                pending.add(AiMessage.from(reply.text()));
                pending.forEach(memory::add);
                return reply;
            }

            request.add(answer);
            pending.add(answer);
            for (ToolExecutionRequest tool : answer.toolExecutionRequests()) {
                ToolExecutionResultMessage result = execute(memoryId, tool);
                request.add(result);
                pending.add(result);
            }
        }

        // Out of calls with the model still reaching for tools. Say something rather than
        // nothing, and commit nothing, so the next turn starts from clean history.
        log.error("Agent did not settle within {} model calls.", MAX_MODEL_CALLS_PER_TURN);
        return Reply.of("Sorry, I got stuck there. Would you like a consultant to call you?");
    }

    /** Everything remembered under {@code memoryId}, oldest first. */
    public List<ChatMessage> history(Object memoryId) {
        return memoryStore.getMessages(memoryId);
    }

    /**
     * Writes a turn the model was never asked about -- an opt-out, or a message arriving after
     * the conversation was handed off. The transcript is what a human reads when they pick the
     * conversation up, so it has to hold everything that was said, not only what cost a call.
     */
    public void record(Object memoryId, String inbound, String outbound) {
        ChatMemory memory = memoryFor(memoryId);
        memory.add(UserMessage.from(inbound));
        memory.add(AiMessage.from(outbound));
    }

    private AiMessage call(List<ChatMessage> messages, boolean withTools) {
        ChatRequest.Builder request = ChatRequest.builder().messages(messages);
        if (withTools) {
            request.toolSpecifications(tools.specifications());
        }
        try {
            return chatModel.chat(request.build()).aiMessage();
        } catch (RuntimeException e) {
            throw new LlmUnavailableException(e);
        }
    }

    /**
     * Runs one tool call. Every failure returns a result the model can act on rather than
     * throwing: a malformed argument should cost a clumsy sentence, not the whole turn.
     */
    private ToolExecutionResultMessage execute(Object memoryId, ToolExecutionRequest call) {
        ToolExecutor executor = tools.find(call.name()).orElse(null);
        if (executor == null) {
            // Models invent tool names. Tell it plainly rather than failing the turn.
            log.warn("Model asked for an unknown tool: {}", call.name());
            return ToolExecutionResultMessage.from(call, "No such tool. Answer without it.");
        }

        try {
            // The executor parses the model's arguments and binds them to the method's
            // parameters, so nothing here needs to know the shape of any particular tool.
            return ToolExecutionResultMessage.from(call, executor.execute(call, memoryId));
        } catch (RuntimeException e) {
            log.error("Tool {} failed on {}: {}", call.name(), call.arguments(), e.getMessage(), e);
            return ToolExecutionResultMessage.from(
                    call, "That lookup failed. Offer a callback rather than guessing.");
        }
    }

    /**
     * Holds the reply to {@code sms.max-parts} messages. One retry rather than a hard trim,
     * because a model asked to be brief writes a better short answer than a truncator does --
     * and the overlong attempt is never persisted, for the same reason a failed call is not.
     */
    private Reply shorten(String text, List<ChatMessage> request) {
        Reply reply = Reply.of(text);
        if (reply.segments() <= maxParts) {
            return reply;
        }

        Reply second = Reply.of(askForShorter(request, text, MessageSegmenter.budget(maxParts)));
        if (second.segments() <= maxParts) {
            return second;
        }

        // Cut at the last full sentence rather than at the character limit: stopping a sentence
        // early reads as terse, stopping mid-sentence reads as broken.
        log.warn("Reply still {} parts after a retry; trimming.", second.segments());
        return Reply.of(MessageSegmenter.trimTo(second.text(), maxParts));
    }

    /**
     * One more call asking for the same thing, shorter. Neither the overlong attempt nor this
     * instruction is ever committed: a model asked to be brief writes a better short answer than
     * a truncator does, and the conversation should not show it being told off.
     */
    private String askForShorter(List<ChatMessage> request, String tooLong, int characters) {
        List<ChatMessage> retry = new ArrayList<>(request);
        retry.add(AiMessage.from(tooLong));
        retry.add(
                UserMessage.from(
                        "That was "
                                + tooLong.length()
                                + " characters, too long to text. Say the important part again in under "
                                + characters
                                + " characters. Drop detail rather than leaving a sentence unfinished."));
        return call(retry, false).text();
    }

    /**
     * The brief, the recipient's facts, and the length budget. The budget is passed in rather
     * than written into the brief, so the number the model is told is the number it is held to --
     * and so the opening can be given a tighter one than the rest of the conversation.
     */
    private SystemMessage systemMessage(Recipient recipient, int budgetCharacters, Prompts prompts) {
        StringBuilder prompt = new StringBuilder(prompts.system());
        prompt.append("\n\nYou are %s, from %s.".formatted(agentName, company));
        // Without this the model cannot turn "tomorrow arvo" into a slot, and will either
        // guess a date or refuse to book at all.
        prompt.append("\n\nRight now it is ").append(NOW.format(ZonedDateTime.now(clock))).append(".");
        if (recipient != null) {
            prompt.append("\n\n").append(recipient.describe());
        }
        prompt.append("\n\nHard limit: every reply must be under ")
                .append(budgetCharacters)
                .append(" characters in total. Finish your sentences within it.");
        return SystemMessage.from(prompt.toString());
    }

    /**
     * A fresh memory per turn. The window reads through to the store, so rebuilding it costs
     * one query and leaves no state to share between concurrent callers.
     */
    private ChatMemory memoryFor(Object memoryId) {
        return MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(maxMessages)
                .chatMemoryStore(memoryStore)
                .build();
    }
}

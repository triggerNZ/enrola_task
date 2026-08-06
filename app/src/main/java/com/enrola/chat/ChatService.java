package com.enrola.chat;

import com.enrola.agent.ChatAgent;
import com.enrola.agent.Recipient;
import com.enrola.agent.Reply;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Conversations as records: which one a turn belongs to, what it is called, when it was last
 * used, and what has been said in it.
 *
 * <p>The exchange with the model belongs to {@link ChatAgent}; this decides which conversation
 * it happens in, and whether the model should be asked at all. Stateless, so concurrent callers
 * on one conversation share nothing.
 */
@Service
public class ChatService {

    private static final int TITLE_MAX_LENGTH = 60;

    private static final String OPT_OUT_REPLY =
            "You're unsubscribed and won't hear from us again. Sorry for the interruption.";

    private static final String CLOSED_REPLY =
            "Thanks — a consultant will be in touch. Nothing more needed from you.";

    private final ChatAgent agent;
    private final ConversationRepository conversations;
    private final LeadRepository leads;
    private final PromptService prompts;

    ChatService(
            ChatAgent agent,
            ConversationRepository conversations,
            LeadRepository leads,
            PromptService prompts) {
        this.agent = agent;
        this.conversations = conversations;
        this.leads = leads;
        this.prompts = prompts;
    }

    public ConversationSummary get(UUID conversationId) {
        return conversations.find(conversationId).orElseThrow(() -> unknown(conversationId));
    }

    public List<ConversationSummary> recent(int limit) {
        return conversations.recentWithMessages(limit);
    }

    public List<MessageView> transcript(UUID conversationId) {
        requireConversation(conversationId);
        return agent.history(conversationId).stream().map(MessageView::of).toList();
    }

    /**
     * One inbound message and what to send back.
     *
     * <p>Two things are decided before the model is involved. Someone opting out is answered and
     * closed on the spot. A conversation already handed off gets an acknowledgement rather than
     * an agent that keeps selling after a consultant owns the relationship.
     */
    public Outcome send(UUID conversationId, String text) {
        if (!StringUtils.hasText(text)) {
            throw new IllegalArgumentException("A message needs some text.");
        }
        ConversationSummary conversation = get(conversationId);

        if (OptOut.matches(text)) {
            record(conversationId, text, OPT_OUT_REPLY);
            conversations.close(conversationId, ConversationSummary.OPTED_OUT, text.strip());
            updateLead(conversation.leadId(), Lead.OPTED_OUT);
            return new Outcome(Reply.of(OPT_OUT_REPLY), get(conversationId));
        }

        if (conversation.closed()) {
            record(conversationId, text, CLOSED_REPLY);
            return new Outcome(Reply.of(CLOSED_REPLY), conversation);
        }

        // The first message of a conversation names it. setTitleIfAbsent is a no-op once a
        // title exists, so later turns cost one cheap update and no bookkeeping here.
        conversations.setTitleIfAbsent(conversationId, truncateTitle(text));
        markEngaged(conversation.leadId());

        Reply reply =
                agent.respondTo(
                        conversationId, text, recipientOf(conversation), prompts.promptsFor(conversationId));
        conversations.touch(conversationId);

        // Re-read: the agent may have closed the conversation by arranging a callback.
        return new Outcome(reply, get(conversationId));
    }

    /**
     * Keeps a turn the agent never saw in the transcript anyway, so a human picking the
     * conversation up sees everything that was actually said.
     */
    private void record(UUID conversationId, String inbound, String outbound) {
        agent.record(conversationId, inbound, outbound);
    }

    private Recipient recipientOf(ConversationSummary conversation) {
        if (conversation.leadId() == null) {
            return null;
        }
        return leads.find(conversation.leadId()).map(LeadService::recipient).orElse(null);
    }

    private void markEngaged(UUID leadId) {
        if (leadId == null) {
            return;
        }
        leads.find(leadId)
                .filter(lead -> Lead.AWAITING_REPLY.equals(lead.status()))
                .ifPresent(lead -> leads.updateStatus(leadId, Lead.ENGAGED));
    }

    private void updateLead(UUID leadId, String status) {
        if (leadId != null) {
            leads.updateStatus(leadId, status);
        }
    }

    private void requireConversation(UUID conversationId) {
        if (!conversations.exists(conversationId)) {
            throw unknown(conversationId);
        }
    }

    private static UnknownConversationException unknown(UUID conversationId) {
        return new UnknownConversationException(conversationId);
    }

    private static String truncateTitle(String text) {
        String trimmed = text.strip();
        return trimmed.length() <= TITLE_MAX_LENGTH
                ? trimmed
                : trimmed.substring(0, TITLE_MAX_LENGTH - 1) + "…";
    }

    /** What to send back, and the state of the conversation once the turn is over. */
    public record Outcome(Reply reply, ConversationSummary conversation) {}
}

package com.enrola.chat;

import com.enrola.agent.ChatAgent;
import com.enrola.agent.Recipient;
import com.enrola.agent.Reply;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Leads and the outreach that starts a conversation with one.
 *
 * <p>Recording a callback lives in {@link CallbackRecorder} rather than here: the agent needs
 * that, and this needs the agent, so keeping them apart is what stops the two from forming a
 * cycle Spring would refuse to wire.
 */
@Service
public class LeadService {

    private final LeadRepository leads;
    private final ConversationRepository conversations;
    private final ChatAgent agent;
    private final PromptService prompts;
    private final Clock clock;

    LeadService(
            LeadRepository leads,
            ConversationRepository conversations,
            ChatAgent agent,
            PromptService prompts,
            Clock clock) {
        this.leads = leads;
        this.conversations = conversations;
        this.agent = agent;
        this.prompts = prompts;
        this.clock = clock;
    }

    public Lead create(
            String name,
            String mobile,
            String email,
            String state,
            String currentProvider,
            BigDecimal currentPremium,
            boolean consent) {
        if (!StringUtils.hasText(name)) {
            throw new IllegalArgumentException("A lead needs a name.");
        }
        if (!StringUtils.hasText(mobile)) {
            throw new IllegalArgumentException("A lead needs a mobile number.");
        }
        UUID id =
                leads.create(
                        name.strip(),
                        mobile.strip(),
                        blankToNull(email),
                        AustralianState.normalise(state),
                        blankToNull(currentProvider),
                        currentPremium,
                        consent ? clock.instant() : null);
        return leads.find(id).orElseThrow();
    }

    public Lead get(UUID id) {
        return leads.find(id).orElseThrow(() -> new UnknownLeadException(id));
    }

    public List<Lead> byStatus(String status, int limit) {
        return leads.byStatus(blankToNull(status), limit);
    }

    public UUID conversationOf(UUID leadId) {
        return conversations.findIdByLead(leadId).orElse(null);
    }

    /**
     * The first message: creates the conversation and has the agent write an opening that knows
     * who it is texting. Refuses rather than contacting someone twice or without consent --
     * both are mistakes that cannot be taken back once a message has gone out.
     */
    @Transactional
    public Outreach startOutreach(UUID leadId) {
        Lead lead = get(leadId);
        if (!lead.hasConsent()) {
            throw new ConflictException("Lead " + leadId + " has not consented to be contacted.");
        }
        if (Lead.OPTED_OUT.equals(lead.status())) {
            throw new ConflictException("Lead " + leadId + " has opted out.");
        }
        if (conversations.findIdByLead(leadId).isPresent()) {
            throw new ConflictException("Lead " + leadId + " has already been contacted.");
        }

        UUID conversationId = conversations.create(leadId, "Outreach to " + lead.name());
        // Fixed here, before the first word is written: the conversation keeps these versions
        // for its whole life, however the prompts are edited afterwards.
        prompts.pin(conversationId);
        Reply opening = agent.open(conversationId, recipient(lead), prompts.promptsFor(conversationId));
        leads.updateStatus(leadId, Lead.AWAITING_REPLY);
        return new Outreach(conversationId, opening);
    }

    static Recipient recipient(Lead lead) {
        return new Recipient(
                lead.name(), lead.state(), lead.currentProvider(), lead.currentPremium());
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.strip() : null;
    }

    /** What outreach produced: the conversation it started, and the message that started it. */
    public record Outreach(UUID conversationId, Reply opening) {}
}

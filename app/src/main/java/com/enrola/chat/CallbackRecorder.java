package com.enrola.chat;

import com.enrola.agent.BookingOutcome;
import com.enrola.agent.CallbackTool;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * What happens when the agent decides someone is ready for a consultant: the slot is taken in
 * the diary and, only then, the conversation closes and the lead is handed off.
 *
 * <p>The order matters. A refused booking has to leave the conversation open, or the person is
 * left with a time they cannot have and an agent that has stopped answering.
 *
 * <p>Its own bean, depending only on repositories and the diary, because {@code LeadService}
 * needs the agent and the agent needs this -- putting them in one class would be a cycle.
 */
@Component
class CallbackRecorder implements CallbackTool {

    /**
     * "Thu 7 Aug, 2:15PM AEST" -- what goes in the note a human reads. The locale is pinned
     * because the default one decides the case of the meridiem, and a note that reads
     * differently depending on the machine that wrote it is a note you cannot search.
     */
    private static final DateTimeFormatter NOTE =
            DateTimeFormatter.ofPattern("EEE d MMM, h:mma z", Locale.ENGLISH);

    private final Diary diary;
    private final ConversationRepository conversations;
    private final LeadRepository leads;

    CallbackRecorder(Diary diary, ConversationRepository conversations, LeadRepository leads) {
        this.diary = diary;
        this.conversations = conversations;
        this.leads = leads;
    }

    @Override
    @Transactional
    public BookingOutcome arrangeCallback(
            Object memoryId, Instant startsAt, String theirWords, String topic) {
        UUID conversationId = (UUID) memoryId;
        UUID leadId = conversations.find(conversationId).map(ConversationSummary::leadId).orElse(null);

        BookingOutcome outcome = diary.book(conversationId, leadId, startsAt, theirWords, topic);
        if (!(outcome instanceof BookingOutcome.Booked booked)) {
            return outcome;
        }

        conversations.close(conversationId, ConversationSummary.CALLBACK, note(booked, topic));
        if (leadId != null) {
            leads.updateStatus(leadId, Lead.HANDED_OFF);
        }
        return outcome;
    }

    @Override
    public BookingOutcome.Unavailable nextAvailable(LocalDate from, int count) {
        return diary.nextAvailable(from, count);
    }

    private static String note(BookingOutcome.Booked booked, String topic) {
        String when = booked.at().format(NOTE);
        return StringUtils.hasText(topic) ? when + " — about " + topic : when;
    }
}

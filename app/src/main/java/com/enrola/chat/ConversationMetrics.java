package com.enrola.chat;

import java.util.List;

/** Outcomes inferred from the transcript for an administrator reviewing a conversation. */
public record ConversationMetrics(boolean calendarBooked, boolean stopped) {

    private static final String CALLBACK_TOOL = "arrange_callback";
    private static final String BOOKED_RESULT = "Booked:";

    public static ConversationMetrics from(List<MessageView> messages) {
        boolean calendarBooked = messages.stream().anyMatch(ConversationMetrics::isBooking);
        boolean stopped = messages.stream().anyMatch(ConversationMetrics::isStop);
        return new ConversationMetrics(calendarBooked, stopped);
    }

    private static boolean isBooking(MessageView message) {
        return CALLBACK_TOOL.equals(message.toolName())
                && message.text() != null
                && message.text().startsWith(BOOKED_RESULT);
    }

    private static boolean isStop(MessageView message) {
        return "USER".equals(message.type()) && OptOut.matches(message.text());
    }
}

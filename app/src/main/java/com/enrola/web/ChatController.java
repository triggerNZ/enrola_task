package com.enrola.web;

import com.enrola.chat.ChatService;
import com.enrola.chat.ConversationSummary;
import com.enrola.chat.ConversationReview;
import com.enrola.chat.MessageView;
import com.enrola.chat.ReviewService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The conversation over HTTP. Conversations are started by outreach to a lead, not here -- see
 * {@link LeadController} -- so this carries the turns and the transcript only.
 */
@RestController
@RequestMapping("/api/conversations")
class ChatController {

    private final ChatService chat;
    private final ReviewService reviews;

    ChatController(ChatService chat, ReviewService reviews) {
        this.chat = chat;
        this.reviews = reviews;
    }

    /** Most recently used first, and only conversations that have history worth resuming. */
    @GetMapping
    public List<ConversationSummary> recent(@RequestParam(defaultValue = "20") int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, got " + limit);
        }
        return chat.recent(limit);
    }

    @GetMapping("/{id}")
    ConversationSummary get(@PathVariable UUID id) {
        return chat.get(id);
    }

    @GetMapping("/{id}/messages")
    List<MessageView> transcript(@PathVariable UUID id) {
        return chat.transcript(id);
    }

    /**
     * The same transcript with whatever an admin made of it. Opt-in, and a second mapping rather
     * than one method returning {@code Object}, so both shapes stay declared. Anything other than
     * {@code review=true} -- absent, false, 1 -- gets the bare array above.
     */
    @GetMapping(value = "/{id}/messages", params = "review=true")
    ReviewedTranscript transcriptWithReview(@PathVariable UUID id) {
        return new ReviewedTranscript(chat.transcript(id), reviews.find(id).orElse(null));
    }

    @PostMapping("/{id}/messages")
    MessageResponse send(@PathVariable UUID id, @RequestBody MessageRequest request) {
        ChatService.Outcome outcome = chat.send(id, request == null ? null : request.text());
        ConversationSummary conversation = outcome.conversation();
        return new MessageResponse(
                outcome.reply().text(),
                outcome.reply().parts(),
                outcome.reply().segments(),
                conversation.closed(),
                conversation.closedReason());
    }

    /** {@code review} is null until somebody has actually reviewed the conversation. */
    record ReviewedTranscript(List<MessageView> messages, ConversationReview review) {}

    record MessageRequest(String text) {}

    /**
     * {@code parts} is the reply split so each piece fits one SMS; {@code segments} is what
     * sending it would cost. {@code closed} tells a client to stop asking -- the agent has
     * handed off, or they opted out.
     */
    record MessageResponse(
            String text, List<String> parts, int segments, boolean closed, String closedReason) {}
}

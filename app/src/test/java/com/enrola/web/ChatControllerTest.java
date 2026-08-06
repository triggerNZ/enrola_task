package com.enrola.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enrola.agent.LlmUnavailableException;
import com.enrola.agent.Reply;
import com.enrola.chat.ChatService;
import com.enrola.chat.ConversationSummary;
import com.enrola.chat.ConversationReview;
import com.enrola.chat.MessageView;
import com.enrola.chat.ReviewService;
import com.enrola.chat.UnknownConversationException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP contract chat.sh relies on: status codes, the {@code {"error": ...}} body, and the
 * field names it reads with jq. The chat itself is mocked -- {@code ChatServiceTest} covers that
 * against a real database.
 */
@WebMvcTest(ChatController.class)
class ChatControllerTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private ChatService chat;
    @MockitoBean private ReviewService reviews;

    private static final UUID ID = UUID.fromString("6f1c4d2e-0000-4000-8000-000000000001");

    private static ConversationSummary open() {
        return new ConversationSummary(ID, null, "hello there", 4, null, null, null);
    }

    private static ConversationSummary closed() {
        return new ConversationSummary(
                ID, null, "hello there", 6, Instant.now(), ConversationSummary.CALLBACK, "tomorrow arvo");
    }

    @Test
    @DisplayName("the listing carries what --resume needs: id and message count, newest first")
    void listReturnsRecentConversations() throws Exception {
        given(chat.recent(1)).willReturn(List.of(open()));

        mvc.perform(get("/api/conversations").param("limit", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ID.toString()))
                .andExpect(jsonPath("$[0].title").value("hello there"))
                .andExpect(jsonPath("$[0].messageCount").value(4));
    }

    @Test
    @DisplayName("a reply arrives as the messages it would take to send")
    void sendReturnsTheReplyInParts() throws Exception {
        given(chat.send(ID, "hello"))
                .willReturn(
                        new ChatService.Outcome(
                                new Reply("part one part two", List.of("part one", "part two")), open()));

        mvc.perform(
                        post("/api/conversations/{id}/messages", ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"text\":\"hello\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("part one part two"))
                .andExpect(jsonPath("$.parts[0]").value("part one"))
                .andExpect(jsonPath("$.parts[1]").value("part two"))
                .andExpect(jsonPath("$.segments").value(2))
                .andExpect(jsonPath("$.closed").value(false));
    }

    @Test
    @DisplayName("a closed conversation says so, so the client stops asking")
    void sendReportsAClosedConversation() throws Exception {
        given(chat.send(ID, "thanks"))
                .willReturn(
                        new ChatService.Outcome(Reply.of("A consultant will call."), closed()));

        mvc.perform(
                        post("/api/conversations/{id}/messages", ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"text\":\"thanks\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closed").value(true))
                .andExpect(jsonPath("$.closedReason").value("callback"));
    }

    @Test
    void unknownConversationIsNotFound() throws Exception {
        willThrow(new UnknownConversationException(ID)).given(chat).send(eq(ID), any());

        mvc.perform(
                        post("/api/conversations/{id}/messages", ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"text\":\"hello\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("No conversation " + ID + "."));
    }

    @Test
    void blankTextIsABadRequest() throws Exception {
        willThrow(new IllegalArgumentException("A message needs some text."))
                .given(chat)
                .send(eq(ID), any());

        mvc.perform(
                        post("/api/conversations/{id}/messages", ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"text\":\"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A message needs some text."));
    }

    @Test
    @DisplayName("an id that is not a UUID is the caller's mistake, not a 500")
    void malformedIdIsABadRequest() throws Exception {
        mvc.perform(get("/api/conversations/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty());
    }

    @Test
    @DisplayName("a failing model is a bad gateway: the request was fine, the upstream was not")
    void llmFailureIsABadGateway() throws Exception {
        willThrow(new LlmUnavailableException(new RuntimeException("upstream is down")))
                .given(chat)
                .send(eq(ID), any());

        mvc.perform(
                        post("/api/conversations/{id}/messages", ID)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"text\":\"hello\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("The language model call failed: upstream is down"));
    }

    @Test
    void transcriptIsReturnedInOrder() throws Exception {
        given(chat.transcript(ID))
                .willReturn(List.of(new MessageView("AI", "hi there"), new MessageView("USER", "hello")));

        mvc.perform(get("/api/conversations/{id}/messages", ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("AI"))
                .andExpect(jsonPath("$[1].type").value("USER"));
    }

    @Test
    @DisplayName("review=true wraps the transcript and adds the verdict")
    void transcriptCanCarryTheReview() throws Exception {
        given(chat.transcript(ID)).willReturn(List.of(new MessageView("AI", "hi there")));
        given(reviews.find(ID))
                .willReturn(
                        java.util.Optional.of(
                                new ConversationReview(ID, 4, "Too pushy on price", "admin", Instant.now())));

        mvc.perform(get("/api/conversations/{id}/messages", ID).param("review", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages[0].text").value("hi there"))
                .andExpect(jsonPath("$.review.rating").value(4))
                .andExpect(jsonPath("$.review.comment").value("Too pushy on price"))
                .andExpect(jsonPath("$.review.reviewer").value("admin"));
    }

    @Test
    @DisplayName("review is null until somebody has actually reviewed it")
    void reviewIsNullWhenUnreviewed() throws Exception {
        given(chat.transcript(ID)).willReturn(List.of(new MessageView("AI", "hi there")));
        given(reviews.find(ID)).willReturn(java.util.Optional.empty());

        mvc.perform(get("/api/conversations/{id}/messages", ID).param("review", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages").isArray())
                .andExpect(jsonPath("$.review").doesNotExist());
    }

    @Test
    @DisplayName("anything other than review=true leaves the bare array alone")
    void anythingElseGetsThePlainArray() throws Exception {
        given(chat.transcript(ID)).willReturn(List.of(new MessageView("AI", "hi there")));

        for (String value : new String[] {"false", "1", "yes"}) {
            mvc.perform(get("/api/conversations/{id}/messages", ID).param("review", value))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$").isArray());
        }
    }
}

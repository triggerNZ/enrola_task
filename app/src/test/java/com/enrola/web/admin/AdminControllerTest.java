package com.enrola.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.enrola.chat.ChatService;
import com.enrola.chat.ConversationReview;
import com.enrola.chat.ConversationSummary;
import com.enrola.chat.MessageView;
import com.enrola.chat.PromptService;
import com.enrola.chat.ReviewService;
import com.enrola.chat.ReviewableConversation;
import com.enrola.chat.UnknownConversationException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import com.enrola.web.SecurityConfig;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** The admin pages: what renders, what redirects, and that a broken link is a page not JSON. */
@WebMvcTest(
        value = AdminController.class,
        properties = {"admin.username=admin", "admin.password=hunter2"})
@Import(SecurityConfig.class) // the real chain, so CSRF and the principal are the shipped ones
class AdminControllerTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private ChatService chat;
    @MockitoBean private ReviewService reviews;
    @MockitoBean private PromptService prompts;

    private static final UUID ID = UUID.fromString("6f1c4d2e-0000-4000-8000-000000000004");

    /** The configured account, as a browser would send it. */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor admin() {
        return httpBasic("admin", "hunter2");
    }

    private static ConversationSummary conversation() {
        return new ConversationSummary(ID, null, "Outreach to Lauren", 4, null, null, null);
    }

    private static ReviewableConversation row(Integer rating) {
        return new ReviewableConversation(
                ID, "Lauren", "Outreach to Lauren", 4, "callback", Instant.now(), rating, null);
    }

    @Test
    void theIndexRedirectsToTheList() throws Exception {
        mvc.perform(get("/admin").with(admin()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/conversations"));
    }

    @Test
    @DisplayName("the list renders with the lead, the count and the rating")
    void listRenders() throws Exception {
        given(reviews.awaitingReview(false, 100)).willReturn(List.of(row(4)));

        mvc.perform(get("/admin/conversations").with(admin()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/conversations"))
                .andExpect(model().attribute("unreviewed", false))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("class=\"side-menu\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("href=\"/admin/prompts\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Lauren")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("4 / 5")));
    }

    @Test
    @DisplayName("the unreviewed filter is passed through, not just shown")
    void unreviewedFilterIsApplied() throws Exception {
        given(reviews.awaitingReview(true, 100)).willReturn(List.of(row(null)));

        mvc.perform(get("/admin/conversations").param("unreviewed", "true").with(admin()))
                .andExpect(status().isOk())
                .andExpect(model().attribute("unreviewed", true));

        then(reviews).should().awaitingReview(true, 100);
    }

    @Test
    @DisplayName("the detail page shows the transcript")
    void detailRendersTheTranscript() throws Exception {
        given(chat.get(ID)).willReturn(conversation());
        given(chat.transcript(ID))
                .willReturn(List.of(new MessageView("AI", "Hi Lauren"), new MessageView("USER", "hello")));
        given(reviews.find(ID)).willReturn(Optional.empty());

        mvc.perform(get("/admin/conversations/{id}", ID).with(admin()))
                .andExpect(status().isOk())
                .andExpect(view().name("admin/conversation"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Hi Lauren")))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.allOf(
                                                org.hamcrest.Matchers.containsString("booking: <span>No</span>"),
                                                org.hamcrest.Matchers.containsString("STOP: <span>No</span>"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Save review")));
    }

    @Test
    @DisplayName("the detail page shows booking and STOP metrics from the transcript")
    void detailShowsConversationMetrics() throws Exception {
        given(chat.get(ID)).willReturn(conversation());
        given(chat.transcript(ID))
                .willReturn(
                        List.of(
                                new MessageView(
                                        "TOOL_EXECUTION_RESULT",
                                        "Booked: Thu 6 Aug 2:15pm AEST.",
                                        List.of(),
                                        "arrange_callback"),
                                new MessageView("USER", "STOP")));
        given(reviews.find(ID)).willReturn(Optional.empty());

        mvc.perform(get("/admin/conversations/{id}", ID).with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Calendar booking:")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("STOP:")))
                .andExpect(
                        content()
                                .string(
                                        org.hamcrest.Matchers.allOf(
                                                org.hamcrest.Matchers.containsString("booking: <span>Yes</span>"),
                                                org.hamcrest.Matchers.containsString("STOP: <span>Yes</span>"))));
    }

    @Test
    @DisplayName("a tool call shows the tool, its arguments and what came back")
    void detailShowsToolCallsInDetail() throws Exception {
        given(chat.get(ID)).willReturn(conversation());
        given(chat.transcript(ID))
                .willReturn(
                        List.of(
                                new MessageView(
                                        "AI",
                                        null,
                                        List.of(
                                                new MessageView.ToolCall(
                                                        "find_policies",
                                                        List.of(
                                                                new MessageView.Argument("tier", "GOLD"),
                                                                new MessageView.Argument(
                                                                        "max_monthly_premium", "250")))),
                                        null),
                                new MessageView(
                                        "TOOL_EXECUTION_RESULT",
                                        "Bupa Complete Gold $289.60/month",
                                        List.of(),
                                        "find_policies")));
        given(reviews.find(ID)).willReturn(Optional.empty());

        String html =
                mvc.perform(get("/admin/conversations/{id}", ID).with(admin()))
                        .andExpect(status().isOk())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(html)
                .contains("find_policies")
                .contains("tier")
                .contains("GOLD")
                .contains("max_monthly_premium")
                .contains("250")
                .contains("returned by")
                .contains("Bupa Complete Gold $289.60/month");
    }

    @Test
    @DisplayName("an existing review comes back in the form, so it can be corrected")
    void detailPrefillsAnExistingReview() throws Exception {
        given(chat.get(ID)).willReturn(conversation());
        given(chat.transcript(ID)).willReturn(List.of());
        given(reviews.find(ID))
                .willReturn(
                        Optional.of(new ConversationReview(ID, 2, "Too pushy", "admin", Instant.now())));

        mvc.perform(get("/admin/conversations/{id}", ID).with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Too pushy")));
    }

    @Test
    @DisplayName("saving records the signed-in reviewer and redirects back")
    void savingRedirects() throws Exception {
        mvc.perform(
                        post("/admin/conversations/{id}/review", ID)
                                .param("rating", "3")
                                .param("comment", "Answered the wrong question")
                                .with(csrf())
                                .with(admin()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/conversations/" + ID));

        then(reviews).should().save(ID, 3, "Answered the wrong question", "admin");
    }

    @Test
    @DisplayName("a form post without a CSRF token is rejected")
    void csrfIsRequiredOnTheForm() throws Exception {
        mvc.perform(post("/admin/conversations/{id}/review", ID).param("rating", "3").with(admin()))
                .andExpect(status().isForbidden());

        then(reviews).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("a stale link is an error page, not a JSON body")
    void unknownConversationRendersAPage() throws Exception {
        willThrow(new UnknownConversationException(ID)).given(chat).get(ID);

        mvc.perform(get("/admin/conversations/{id}", ID).with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(view().name("admin/error"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("No conversation")));
    }

    @Test
    @DisplayName("a rating the service refuses becomes a page, not a stack trace")
    void aBadRatingRendersAPage() throws Exception {
        willThrow(new IllegalArgumentException("Rating must be between 1 and 5, got 9"))
                .given(reviews)
                .save(eq(ID), eq(9), any(), any());

        mvc.perform(
                        post("/admin/conversations/{id}/review", ID)
                                .param("rating", "9")
                                .with(csrf())
                                .with(admin()))
                .andExpect(status().isBadRequest())
                .andExpect(view().name("admin/error"));
    }

    @Test
    @DisplayName("a comment is escaped, not rendered")
    void commentsAreEscaped() throws Exception {
        given(chat.get(ID)).willReturn(conversation());
        given(chat.transcript(ID)).willReturn(List.of());
        given(reviews.find(ID))
                .willReturn(
                        Optional.of(
                                new ConversationReview(
                                        ID, 1, "<script>alert(1)</script>", "admin", Instant.now())));

        mvc.perform(get("/admin/conversations/{id}", ID).with(admin()))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("<script>alert(1)</script>"))))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("&lt;script&gt;")));
    }
}

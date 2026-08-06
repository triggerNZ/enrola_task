package com.enrola.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import com.enrola.agent.Reply;
import com.enrola.chat.ChatService;
import com.enrola.chat.ConversationSummary;
import com.enrola.chat.LeadService;
import com.enrola.chat.ReviewService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The two rules that matter after putting Spring Security on the classpath: the admin pages are
 * shut, and nothing else moved.
 *
 * <p>The real filter chain, not a slice, because the thing worth proving is what the shipped
 * configuration does. No database is needed -- Hikari connects lazily and Flyway is off, the same
 * trick {@code EnrolaApplicationTests} uses.
 */
@SpringBootTest(
        properties = {
            "openai.api-key=test-key",
            "admin.username=admin",
            "admin.password=hunter2",
            "spring.flyway.enabled=false",
            // No database here, so nothing to seed into.
            "prompts.seed-on-start=false"
        })
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired private MockMvc mvc;

    // Mocked so a request that gets past security reaches a controller and comes back with a
    // status, rather than dying on a database that is not there.
    @MockitoBean private ChatService chat;
    @MockitoBean private ReviewService reviews;
    @MockitoBean private LeadService leads;

    @Test
    @DisplayName("the admin pages are shut to anyone without credentials")
    void adminNeedsAuthentication() throws Exception {
        mvc.perform(get("/admin/conversations")).andExpect(status().isUnauthorized());
        mvc.perform(get("/admin")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("and open with them")
    void adminAcceptsTheConfiguredUser() throws Exception {
        int status =
                mvc.perform(get("/admin/conversations").with(basic("admin", "hunter2")))
                        .andReturn()
                        .getResponse()
                        .getStatus();

        // Whatever it renders, it is past the door -- there is no database behind it here.
        assertThat(status).isNotIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value());
    }

    @Test
    void theWrongPasswordIsRefused() throws Exception {
        mvc.perform(get("/admin/conversations").with(basic("admin", "wrong")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("the API is untouched: adding security must not take chat.sh down with it")
    void theApiStaysOpen() throws Exception {
        int status = mvc.perform(get("/api/conversations")).andReturn().getResponse().getStatus();

        assertThat(status).isNotIn(HttpStatus.UNAUTHORIZED.value(), HttpStatus.FORBIDDEN.value());
    }

    @Test
    @DisplayName("posts are not blocked by CSRF in the demo")
    void csrfIsDisabled() throws Exception {
        given(chat.send(any(), any()))
                .willReturn(
                        new ChatService.Outcome(
                                Reply.of("ok"),
                                new ConversationSummary(UUID.randomUUID(), null, null, 1, null, null, null)));

        int status =
                mvc.perform(
                                post("/api/conversations/6f1c4d2e-0000-4000-8000-000000000009/messages")
                                        .contentType("application/json")
                                        .content("{\"text\":\"hello\"}"))
                        .andReturn()
                        .getResponse()
                        .getStatus();

        // A 403 here would mean the CSRF filter rejected it before it reached the controller.
        assertThat(status).isNotEqualTo(HttpStatus.FORBIDDEN.value());
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor basic(
            String user, String password) {
        return org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                .httpBasic(user, password);
    }
}

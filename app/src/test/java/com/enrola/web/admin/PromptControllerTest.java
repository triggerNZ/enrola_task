package com.enrola.web.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.enrola.chat.Prompt;
import com.enrola.chat.PromptKind;
import com.enrola.chat.PromptService;
import com.enrola.web.SecurityConfig;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/** Reading and editing prompts through the pages. */
@WebMvcTest(
        value = PromptController.class,
        properties = {"admin.username=admin", "admin.password=hunter2"})
@Import(SecurityConfig.class)
class PromptControllerTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private PromptService prompts;

    private static RequestPostProcessor admin() {
        return httpBasic("admin", "hunter2");
    }

    private static Prompt prompt(PromptKind kind, int version, boolean current) {
        return new Prompt(
                UUID.randomUUID(), kind, version, "You text people who…", current, Instant.now(), "admin");
    }

    @Test
    @DisplayName("the index lists each prompt and its current version")
    void indexListsCurrentVersions() throws Exception {
        for (PromptKind kind : PromptKind.values()) {
            given(prompts.current(kind)).willReturn(Optional.of(prompt(kind, 2, true)));
        }

        String html =
                mvc.perform(get("/admin/prompts").with(admin()))
                        .andExpect(status().isOk())
                        .andExpect(view().name("admin/prompts"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(html)
                .contains("class=\"side-menu\"")
                .contains("href=\"/admin/conversations\"")
                .contains("Agent brief")
                .contains("Health insurance FAQ")
                .contains("v2");
    }

    @Test
    @DisplayName("the history shows every version, and which one is live")
    void historyShowsEveryVersion() throws Exception {
        given(prompts.history(PromptKind.BRIEF))
                .willReturn(
                        List.of(
                                prompt(PromptKind.BRIEF, 3, true),
                                prompt(PromptKind.BRIEF, 2, false),
                                prompt(PromptKind.BRIEF, 1, false)));

        String html =
                mvc.perform(get("/admin/prompts/brief").with(admin()))
                        .andExpect(status().isOk())
                        .andExpect(view().name("admin/prompt-history"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(html).contains("v3").contains("v2").contains("v1").contains("current").contains("superseded");
    }

    @Test
    @DisplayName("an old version opens in an editable form, and says it is superseded")
    void anOldVersionIsEditable() throws Exception {
        given(prompts.find(PromptKind.OUTREACH, 1))
                .willReturn(Optional.of(prompt(PromptKind.OUTREACH, 1, false)));

        String html =
                mvc.perform(get("/admin/prompts/outreach/1").with(admin()))
                        .andExpect(status().isOk())
                        .andExpect(view().name("admin/prompt"))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(html)
                .contains("Outreach instruction v1")
                .contains("You text people who")
                .contains("superseded")
                .contains("Save as a new current version");
    }

    @Test
    @DisplayName("saving records the signed-in author and returns to the history")
    void savingAppendsAndRedirects() throws Exception {
        given(prompts.save(PromptKind.BRIEF, "A new brief.", "admin"))
                .willReturn(prompt(PromptKind.BRIEF, 4, true));

        mvc.perform(
                        post("/admin/prompts/brief")
                                .param("body", "A new brief.")
                                .with(csrf())
                                .with(admin()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/admin/prompts/brief"));

        then(prompts).should().save(PromptKind.BRIEF, "A new brief.", "admin");
    }

    @Test
    @DisplayName("a prompt that does not exist is a page, not a stack trace")
    void unknownKindIsAPage() throws Exception {
        mvc.perform(get("/admin/prompts/nonsense").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(view().name("admin/error"));
    }

    @Test
    void unknownVersionIsAPage() throws Exception {
        given(prompts.find(PromptKind.BRIEF, 99)).willReturn(Optional.empty());

        mvc.perform(get("/admin/prompts/brief/99").with(admin()))
                .andExpect(status().isNotFound())
                .andExpect(view().name("admin/error"));
    }

    @Test
    @DisplayName("prompts are behind the same door as everything else under /admin")
    void promptsNeedAuthentication() throws Exception {
        mvc.perform(get("/admin/prompts")).andExpect(status().isUnauthorized());
        mvc.perform(post("/admin/prompts/brief").param("body", "x").with(csrf()))
                .andExpect(status().isUnauthorized());
    }
}

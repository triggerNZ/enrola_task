package com.enrola.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.enrola.agent.Reply;
import com.enrola.chat.ConflictException;
import com.enrola.chat.Lead;
import com.enrola.chat.LeadService;
import com.enrola.chat.UnknownLeadException;
import java.math.BigDecimal;
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

/** The entry point to the flow: creating a lead, and sending the first message. */
@WebMvcTest(LeadController.class)
class LeadControllerTest {

    @Autowired private MockMvc mvc;

    @MockitoBean private LeadService leads;

    private static final UUID ID = UUID.fromString("6f1c4d2e-0000-4000-8000-000000000002");
    private static final UUID CONVERSATION = UUID.fromString("6f1c4d2e-0000-4000-8000-000000000003");

    private static Lead sam() {
        return new Lead(
                ID, "Sam", "+61400000000", "sam@example.com", "QLD", "Bupa",
                new BigDecimal("250.00"), Instant.now(), Lead.NEW, Instant.now());
    }

    @Test
    void createReturnsTheLeadAndItsLocation() throws Exception {
        given(leads.create(any(), any(), any(), any(), any(), any(), anyBoolean())).willReturn(sam());

        mvc.perform(
                        post("/api/leads")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"name":"Sam","mobile":"+61400000000","email":"sam@example.com",
                                         "state":"QLD","currentProvider":"Bupa","currentPremium":250.00,
                                         "consent":true}
                                        """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/leads/" + ID))
                .andExpect(jsonPath("$.id").value(ID.toString()))
                .andExpect(jsonPath("$.currentProvider").value("Bupa"))
                .andExpect(jsonPath("$.currentPremium").value(250.00))
                .andExpect(jsonPath("$.consent").value(true))
                .andExpect(jsonPath("$.status").value("new"));
    }

    @Test
    @DisplayName("a lead with no mobile is a bad request, not a 500")
    void missingMobileIsABadRequest() throws Exception {
        willThrow(new IllegalArgumentException("A lead needs a mobile number."))
                .given(leads)
                .create(any(), any(), any(), any(), any(), any(), anyBoolean());

        mvc.perform(
                        post("/api/leads")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"name\":\"Sam\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("A lead needs a mobile number."));
    }

    @Test
    void anUnknownStateIsABadRequest() throws Exception {
        willThrow(new IllegalArgumentException("Not an Australian state or territory: Queensland."))
                .given(leads)
                .create(any(), any(), any(), eq("Queensland"), any(), any(), anyBoolean());

        mvc.perform(
                        post("/api/leads")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"name\":\"Sam\",\"mobile\":\"+61400000000\",\"state\":\"Queensland\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("Queensland")));
    }

    @Test
    void listFiltersByStatus() throws Exception {
        given(leads.byStatus("awaiting_reply", 20)).willReturn(List.of(sam()));

        mvc.perform(get("/api/leads").param("status", "awaiting_reply"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(ID.toString()));
    }

    @Test
    void unknownLeadIsNotFound() throws Exception {
        willThrow(new UnknownLeadException(ID)).given(leads).get(ID);

        mvc.perform(get("/api/leads/{id}", ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("No lead " + ID + "."));
    }

    @Test
    @DisplayName("outreach returns the opening message and where the conversation lives")
    void outreachReturnsTheOpening() throws Exception {
        given(leads.startOutreach(ID))
                .willReturn(
                        new LeadService.Outreach(
                                CONVERSATION, Reply.of("Hi Sam, saw you were comparing cover.")));

        mvc.perform(post("/api/leads/{id}/outreach", ID))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/conversations/" + CONVERSATION))
                .andExpect(jsonPath("$.conversationId").value(CONVERSATION.toString()))
                .andExpect(jsonPath("$.text").value("Hi Sam, saw you were comparing cover."))
                .andExpect(jsonPath("$.parts[0]").value("Hi Sam, saw you were comparing cover."))
                .andExpect(jsonPath("$.segments").value(1));
    }

    @Test
    @DisplayName("contacting someone twice, or without consent, is a conflict")
    void outreachConflicts() throws Exception {
        willThrow(new ConflictException("Lead " + ID + " has already been contacted."))
                .given(leads)
                .startOutreach(ID);

        mvc.perform(post("/api/leads/{id}/outreach", ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(containsString("already")));
    }

    @Test
    void createWithNoBodyAtAllIsABadRequest() throws Exception {
        mvc.perform(post("/api/leads").contentType(MediaType.APPLICATION_JSON).content(""))
                .andExpect(status().isBadRequest());
    }
}

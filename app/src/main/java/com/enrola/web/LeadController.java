package com.enrola.web;

import com.enrola.chat.Lead;
import com.enrola.chat.LeadService;
import java.math.BigDecimal;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Leads, and the outreach that opens a conversation with one. This is the entry point to the
 * whole flow: no lead, no conversation.
 */
@RestController
@RequestMapping("/api/leads")
class LeadController {

    private final LeadService leads;

    LeadController(LeadService leads) {
        this.leads = leads;
    }

    @PostMapping
    ResponseEntity<LeadView> create(@RequestBody NewLead request) {
        if (request == null) {
            throw new IllegalArgumentException("A lead needs a name and a mobile number.");
        }
        Lead created =
                leads.create(
                        request.name(),
                        request.mobile(),
                        request.email(),
                        request.state(),
                        request.currentProvider(),
                        request.currentPremium(),
                        // Absent consent is treated as none: contacting someone who never agreed
                        // is the one mistake here that cannot be undone.
                        Boolean.TRUE.equals(request.consent()));
        return ResponseEntity.created(URI.create("/api/leads/" + created.id())).body(view(created));
    }

    @GetMapping
    List<LeadView> list(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "20") int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be at least 1, got " + limit);
        }
        return leads.byStatus(status, limit).stream().map(this::view).toList();
    }

    @GetMapping("/{id}")
    LeadView get(@PathVariable UUID id) {
        return view(leads.get(id));
    }

    /** Sends the first message. Refuses a second one, and refuses without consent. */
    @PostMapping("/{id}/outreach")
    ResponseEntity<OutreachView> outreach(@PathVariable UUID id) {
        LeadService.Outreach outreach = leads.startOutreach(id);
        return ResponseEntity.created(URI.create("/api/conversations/" + outreach.conversationId()))
                .body(
                        new OutreachView(
                                outreach.conversationId(),
                                outreach.opening().text(),
                                outreach.opening().parts(),
                                outreach.opening().segments()));
    }

    private LeadView view(Lead lead) {
        return new LeadView(
                lead.id(),
                lead.name(),
                lead.mobile(),
                lead.email(),
                lead.state(),
                lead.currentProvider(),
                lead.currentPremium(),
                lead.hasConsent(),
                lead.status(),
                leads.conversationOf(lead.id()));
    }

    record NewLead(
            String name,
            String mobile,
            String email,
            String state,
            String currentProvider,
            BigDecimal currentPremium,
            Boolean consent) {}

    record LeadView(
            UUID id,
            String name,
            String mobile,
            String email,
            String state,
            String currentProvider,
            BigDecimal currentPremium,
            boolean consent,
            String status,
            UUID conversationId) {}

    record OutreachView(UUID conversationId, String text, List<String> parts, int segments) {}
}

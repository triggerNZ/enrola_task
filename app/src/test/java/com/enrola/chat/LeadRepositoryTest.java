package com.enrola.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"openai.api-key=test-key", "admin.password=test"})
class LeadRepositoryTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Autowired private LeadRepository leads;

    private UUID switcher(String name, String status) {
        UUID id =
                leads.create(
                        name, "+61400000000", name + "@example.com", "QLD", "Bupa",
                        new BigDecimal("250.00"), Instant.now());
        leads.updateStatus(id, status);
        return id;
    }

    @Test
    @DisplayName("every field survives the round trip, premium included")
    void roundTripsALead() {
        UUID id =
                leads.create(
                        "Sam", "+61400111222", "sam@example.com", "QLD", "Bupa",
                        new BigDecimal("250.00"), Instant.now());

        assertThat(leads.find(id))
                .get()
                .satisfies(
                        lead -> {
                            assertThat(lead.name()).isEqualTo("Sam");
                            assertThat(lead.mobile()).isEqualTo("+61400111222");
                            assertThat(lead.email()).isEqualTo("sam@example.com");
                            assertThat(lead.state()).isEqualTo("QLD");
                            assertThat(lead.currentProvider()).isEqualTo("Bupa");
                            // Money, so the scale matters: 250.00, not 250.0 or 249.99999.
                            assertThat(lead.currentPremium()).isEqualByComparingTo("250.00");
                            assertThat(lead.hasConsent()).isTrue();
                            assertThat(lead.status()).isEqualTo(Lead.NEW);
                        });
    }

    @Test
    @DisplayName("a first-timer keeps its nulls rather than inventing a provider")
    void aLeadWithNoCoverYet() {
        UUID id = leads.create("Alex", "+61400333444", null, "VIC", null, null, null);

        assertThat(leads.find(id))
                .get()
                .satisfies(
                        lead -> {
                            assertThat(lead.currentProvider()).isNull();
                            assertThat(lead.currentPremium()).isNull();
                            assertThat(lead.hasConsent()).isFalse();
                        });
    }

    @Test
    void findsNothingForAnUnknownId() {
        assertThat(leads.find(UUID.randomUUID())).isEmpty();
    }

    @Test
    @DisplayName("the listing filters by status and honours the limit")
    void byStatusFilters() {
        UUID waiting = switcher("Waiting", Lead.AWAITING_REPLY);
        UUID handedOff = switcher("HandedOff", Lead.HANDED_OFF);

        assertThat(leads.byStatus(Lead.AWAITING_REPLY, 100))
                .extracting(Lead::id)
                .contains(waiting)
                .doesNotContain(handedOff);
        // Other tests share the database, so assert these rows are present rather than alone.
        assertThat(leads.byStatus(null, 100)).extracting(Lead::id).contains(waiting, handedOff);
        assertThat(leads.byStatus(null, 1)).hasSize(1);
    }

    @Test
    void updateStatusMovesTheLeadAlong() {
        UUID id = switcher("Mover", Lead.NEW);

        leads.updateStatus(id, Lead.ENGAGED);

        assertThat(leads.find(id)).get().extracting(Lead::status).isEqualTo(Lead.ENGAGED);
    }
}

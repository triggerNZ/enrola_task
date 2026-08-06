package com.enrola.chat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Lead rows: creating them, finding them, and moving them along the pipeline. */
@Repository
class LeadRepository {

    private static final String COLUMNS =
            """
            id, name, mobile, email, state, current_provider, current_premium,
            consent_at, status, created_at
            """;

    private final JdbcClient db;

    LeadRepository(JdbcClient db) {
        this.db = db;
    }

    UUID create(
            String name,
            String mobile,
            String email,
            String state,
            String currentProvider,
            BigDecimal currentPremium,
            Instant consentAt) {
        UUID id = UUID.randomUUID();
        db.sql(
                        """
                        insert into lead (id, name, mobile, email, state, current_provider,
                                          current_premium, consent_at)
                        values (:id, :name, :mobile, :email, :state, :provider, :premium, :consentAt)
                        """)
                .param("id", id)
                .param("name", name)
                .param("mobile", mobile)
                .param("email", email)
                .param("state", state)
                .param("provider", currentProvider)
                .param("premium", currentPremium)
                // PgJDBC cannot infer a SQL type for Instant; it takes OffsetDateTime.
                .param("consentAt", consentAt == null ? null : consentAt.atOffset(ZoneOffset.UTC))
                .update();
        return id;
    }

    Optional<Lead> find(UUID id) {
        return db.sql("select " + COLUMNS + " from lead where id = :id")
                .param("id", id)
                .query(Lead.class)
                .optional();
    }

    /** Most recently created first. A blank status means every lead. */
    List<Lead> byStatus(String status, int limit) {
        return db.sql(
                        """
                        select %s
                          from lead
                         -- The cast is required: Postgres cannot infer a type for a bare
                         -- parameter on its own in `? is null`.
                         where (cast(:status as text) is null or status = :status)
                         order by created_at desc
                         limit :limit
                        """
                                .formatted(COLUMNS))
                .param("status", status)
                .param("limit", limit)
                .query(Lead.class)
                .list();
    }

    void updateStatus(UUID id, String status) {
        db.sql("update lead set status = :status, updated_at = now() where id = :id")
                .param("id", id)
                .param("status", status)
                .update();
    }
}

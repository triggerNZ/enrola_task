package com.enrola.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.enrola.agent.Prompts;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Versioned prompts against a real Postgres, including the seeding that runs at startup -- the
 * container comes up empty, so the seeder in this context has already done its job.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"openai.api-key=test-key", "admin.password=test"})
class PromptServiceTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Autowired private PromptService prompts;
    @Autowired private PromptRepository repository;
    @Autowired private PromptSeeder seeder;
    @Autowired private JdbcClient db;

    @Nested
    @DisplayName("seeding")
    class Seeding {

        // These read v1 rather than "the current version": v1 is what seeding produced and is
        // immutable, whereas current moves the moment any other test in this class saves.

        @Test
        @DisplayName("every kind arrives at v1, with no author")
        void seedsEachKindOnce() {
            for (PromptKind kind : PromptKind.values()) {
                assertThat(prompts.find(kind, 1))
                        .as("%s v1", kind)
                        .get()
                        .satisfies(
                                prompt -> {
                                    assertThat(prompt.createdBy()).isNull();
                                    assertThat(prompt.body()).isNotBlank();
                                });
            }
        }

        @Test
        @DisplayName("v1 is the real file, not a placeholder")
        void seedsTheActualFiles() {
            assertThat(prompts.find(PromptKind.BRIEF, 1).orElseThrow().body())
                    .contains("Every message is an SMS");
            assertThat(prompts.find(PromptKind.FAQ, 1).orElseThrow().body())
                    .contains("Lifetime Health Cover");
            assertThat(prompts.find(PromptKind.OUTREACH, 1).orElseThrow().body())
                    .contains("Open the conversation");
        }

        @Test
        @DisplayName("running again adds nothing: it is a first-start job, not a reset")
        void isIdempotent() {
            int before = repository.history(PromptKind.BRIEF).size();

            seeder.run(null);

            assertThat(repository.history(PromptKind.BRIEF)).hasSize(before);
        }
    }

    @Nested
    @DisplayName("saving")
    class Saving {

        @Test
        @DisplayName("a save appends the next version and moves current onto it")
        void appendsAndBecomesCurrent() {
            int startingVersion = prompts.current(PromptKind.OUTREACH).orElseThrow().version();

            Prompt saved = prompts.save(PromptKind.OUTREACH, "Say hello, briefly.", "admin");

            assertThat(saved.version()).isEqualTo(startingVersion + 1);
            assertThat(saved.current()).isTrue();
            assertThat(saved.createdBy()).isEqualTo("admin");
            assertThat(prompts.current(PromptKind.OUTREACH)).get().isEqualTo(saved);
        }

        @Test
        @DisplayName("the version it replaced is kept, and readable, exactly as it was")
        void theOldVersionSurvives() {
            Prompt first = prompts.current(PromptKind.BRIEF).orElseThrow();

            prompts.save(PromptKind.BRIEF, "Something else entirely.", "admin");

            assertThat(prompts.find(PromptKind.BRIEF, first.version()))
                    .get()
                    .satisfies(
                            old -> {
                                assertThat(old.body()).isEqualTo(first.body());
                                assertThat(old.current()).isFalse();
                            });
        }

        @Test
        @DisplayName("only one version of a kind is ever current")
        void oneCurrentPerKind() {
            prompts.save(PromptKind.FAQ, "One.", "admin");
            prompts.save(PromptKind.FAQ, "Two.", "admin");
            prompts.save(PromptKind.FAQ, "Three.", "admin");

            assertThat(repository.currentCount(PromptKind.FAQ)).isEqualTo(1);
            assertThat(prompts.current(PromptKind.FAQ)).get().extracting(Prompt::body).isEqualTo("Three.");
        }

        @Test
        @DisplayName("saving an old version appends rather than branching -- reverting is just an edit")
        void revertingIsAnEdit() {
            Prompt original = prompts.current(PromptKind.OUTREACH).orElseThrow();
            prompts.save(PromptKind.OUTREACH, "A detour.", "admin");

            Prompt reverted = prompts.save(PromptKind.OUTREACH, original.body(), "admin");

            assertThat(reverted.version()).isGreaterThan(original.version() + 1);
            assertThat(reverted.body()).isEqualTo(original.body());
            assertThat(reverted.current()).isTrue();
        }

        @Test
        void historyIsNewestFirst() {
            prompts.save(PromptKind.BRIEF, "Newer.", "admin");

            assertThat(prompts.history(PromptKind.BRIEF))
                    .extracting(Prompt::version)
                    .isSortedAccordingTo(java.util.Comparator.reverseOrder());
        }

        @Test
        void anEmptyPromptIsRefused() {
            assertThatThrownBy(() -> prompts.save(PromptKind.BRIEF, "   ", "admin"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("cannot be empty");
        }

        @Test
        @DisplayName("the index refuses a second current version, whatever the service thinks")
        void theDatabaseEnforcesOneCurrent() {
            assertThatThrownBy(
                            () ->
                                    db.sql(
                                                    """
                                                    insert into prompt (id, kind, version, body, is_current)
                                                    values (:id, 'BRIEF', 9999, 'sneaky', true)
                                                    """)
                                            .param("id", UUID.randomUUID())
                                            .update())
                    .isInstanceOf(DuplicateKeyException.class);
        }
    }

    @Nested
    @DisplayName("composing")
    class Composing {

        @Test
        @DisplayName("the system prompt is the brief and the FAQ, in that order")
        void systemIsBriefThenFaq() {
            prompts.save(PromptKind.BRIEF, "THE BRIEF", "admin");
            prompts.save(PromptKind.FAQ, "THE FACTS", "admin");
            prompts.save(PromptKind.OUTREACH, "THE OPENER", "admin");

            Prompts composed = prompts.currentPrompts();

            assertThat(composed.system()).isEqualTo("THE BRIEF\n\nTHE FACTS");
            assertThat(composed.outreach()).isEqualTo("THE OPENER");
        }
    }
}

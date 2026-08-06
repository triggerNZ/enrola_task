package com.enrola.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Reviews against a real Postgres: what the service refuses, and what the table refuses. */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {"openai.api-key=test-key", "admin.password=test"})
class ReviewServiceTest {

    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Autowired private ReviewService reviews;
    @Autowired private ReviewRepository repository;
    @Autowired private ConversationRepository conversations;
    @Autowired private PostgresChatMemoryStore store;
    @Autowired private JdbcClient db;

    private UUID conversation() {
        return conversations.create(null, "reviewed");
    }

    @Nested
    @DisplayName("saving")
    class Saving {

        @Test
        void keepsTheRatingCommentAndReviewer() {
            UUID id = conversation();

            ConversationReview saved = reviews.save(id, 4, "Too pushy on price", "admin");

            assertThat(saved.rating()).isEqualTo(4);
            assertThat(saved.comment()).isEqualTo("Too pushy on price");
            assertThat(saved.reviewer()).isEqualTo("admin");
            assertThat(saved.updatedAt()).isNotNull();
        }

        @Test
        @DisplayName("reviewing again replaces the verdict rather than adding a second one")
        void isAnUpsert() {
            UUID id = conversation();

            reviews.save(id, 2, "Missed the question", "admin");
            reviews.save(id, 5, "Actually fine on a reread", "admin");

            assertThat(repository.count(id)).isEqualTo(1);
            assertThat(reviews.find(id))
                    .get()
                    .satisfies(
                            review -> {
                                assertThat(review.rating()).isEqualTo(5);
                                assertThat(review.comment()).isEqualTo("Actually fine on a reread");
                            });
        }

        @Test
        @DisplayName("a rating on its own is a real opinion, so the comment may be blank")
        void commentIsOptional() {
            UUID id = conversation();

            assertThat(reviews.save(id, 3, "   ", "admin").comment()).isNull();
            assertThat(reviews.save(id, 3, null, "admin").comment()).isNull();
        }

        @Test
        void trimsTheComment() {
            UUID id = conversation();

            assertThat(reviews.save(id, 3, "  needs work  ", "admin").comment()).isEqualTo("needs work");
        }
    }

    @Nested
    @DisplayName("what is refused")
    class Refused {

        @Test
        @DisplayName("out of five means out of five")
        void ratingMustBeOneToFive() {
            UUID id = conversation();

            for (int rating : new int[] {0, -1, 6, 100}) {
                assertThatThrownBy(() -> reviews.save(id, rating, null, "admin"))
                        .as("rating %d", rating)
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("between 1 and 5");
            }
            assertThat(reviews.find(id)).isEmpty();
        }

        @Test
        void anEnormousCommentIsRefused() {
            UUID id = conversation();

            assertThatThrownBy(() -> reviews.save(id, 3, "x".repeat(2001), "admin"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("2000");
        }

        @Test
        void aConversationThatDoesNotExistIsRefused() {
            assertThatThrownBy(() -> reviews.save(UUID.randomUUID(), 3, null, "admin"))
                    .isInstanceOf(UnknownConversationException.class);
        }

        @Test
        @DisplayName("the table refuses a bad rating too, whatever the service thinks")
        void theCheckConstraintIsRealTooReview() {
            UUID id = conversation();

            assertThatThrownBy(
                            () ->
                                    db.sql(
                                                    "insert into conversation_review (conversation_id, rating)"
                                                            + " values (:id, 9)")
                                            .param("id", id)
                                            .update())
                    .isInstanceOf(DataIntegrityViolationException.class);
        }
    }

    @Nested
    @DisplayName("the listing")
    class Listing {

        @Test
        @DisplayName("carries the lead, the count and the rating, and skips empty conversations")
        void showsWhatIsNeededToChooseWhatToRead() {
            UUID id = conversation();
            store.updateMessages(id, java.util.List.of(dev.langchain4j.data.message.UserMessage.from("hi")));
            reviews.save(id, 4, "fine", "admin");

            assertThat(reviews.awaitingReview(false, 100))
                    .filteredOn(row -> row.id().equals(id))
                    .singleElement()
                    .satisfies(
                            row -> {
                                assertThat(row.messageCount()).isEqualTo(1);
                                assertThat(row.rating()).isEqualTo(4);
                                assertThat(row.reviewed()).isTrue();
                            });
        }

        @Test
        @DisplayName("the unreviewed filter leaves out anything already rated")
        void unreviewedOnly() {
            UUID rated = conversation();
            UUID unrated = conversation();
            store.updateMessages(rated, java.util.List.of(dev.langchain4j.data.message.UserMessage.from("a")));
            store.updateMessages(unrated, java.util.List.of(dev.langchain4j.data.message.UserMessage.from("b")));
            reviews.save(rated, 5, null, "admin");

            assertThat(reviews.awaitingReview(true, 100))
                    .extracting(ReviewableConversation::id)
                    .contains(unrated)
                    .doesNotContain(rated);
        }

        @Test
        void aDeletedConversationTakesItsReviewWithIt() {
            UUID id = conversation();
            reviews.save(id, 3, "gone soon", "admin");

            db.sql("delete from conversation where id = :id").param("id", id).update();

            assertThat(reviews.find(id)).isEmpty();
        }
    }
}

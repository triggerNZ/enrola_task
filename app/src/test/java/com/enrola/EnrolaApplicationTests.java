package com.enrola;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * Checks the context wires up. Deliberately needs no database: Hikari connects lazily, and
 * Flyway is switched off here so this stays a pure wiring check. Real database behaviour is
 * covered by {@link PostgresChatMemoryStoreTest} and {@link ConversationRepositoryTest}.
 */
@SpringBootTest(
        properties = {
            // @SpringBootTest runs ApplicationRunner beans, so without this ChatRunner would
            // start a chat -- against the real API and database -- during the test.
            "openai.api-key=",
            "spring.flyway.enabled=false"
        })
class EnrolaApplicationTests {

    @Autowired private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }
}

package com.enrola;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.ApplicationContext;

/**
 * Checks the context wires up. Deliberately needs no database: Hikari connects lazily, and
 * Flyway is switched off here so this stays a pure wiring check. Real database behaviour is
 * covered by {@link PostgresChatMemoryStoreTest} and {@link ConversationRepositoryTest}.
 */
@SpringBootTest(
        properties = {
            // A placeholder key, because LlmConfig refuses to build the model without one.
            // Nothing here calls the model, so no request is ever made.
            "openai.api-key=test-key",
            "admin.password=test",
            "spring.flyway.enabled=false",
            // No database here, so nothing to seed into.
            "prompts.seed-on-start=false"
        })
class EnrolaApplicationTests {

    @Autowired private ApplicationContext context;

    @Test
    void contextLoads() {
        assertThat(context).isNotNull();
    }
}

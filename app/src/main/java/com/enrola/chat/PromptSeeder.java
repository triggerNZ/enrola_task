package com.enrola.chat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Puts version 1 of each instruction into the database, from the copy in the repo.
 *
 * <p>An {@link ApplicationRunner} so it runs after Flyway has built the tables. Idempotent by
 * kind: a kind that already has any version is left alone, so this is a first-start job and a
 * no-op on every start after that. If two instances ever raced here the unique index on
 * {@code (kind, version)} would fail one of them loudly rather than leave two version ones.
 */
@Component
// On by default. Off is for the tests that deliberately run without a database, and for any
// deployment that would rather write the first versions itself.
@ConditionalOnProperty(name = "prompts.seed-on-start", havingValue = "true", matchIfMissing = true)
class PromptSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PromptSeeder.class);

    private final PromptService prompts;

    PromptSeeder(PromptService prompts) {
        this.prompts = prompts;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<PromptKind> missing = prompts.unseeded();
        if (missing.isEmpty()) {
            log.info("Prompts already seeded; the database is the source of truth now.");
            return;
        }
        for (PromptKind kind : missing) {
            prompts.seed(kind, read(kind.seedResource()));
            log.info("Seeded {} v1 from {}.", kind.displayName(), kind.seedResource());
        }
    }

    private static String read(String path) {
        try (var in = new ClassPathResource(path).getInputStream()) {
            return StreamUtils.copyToString(in, StandardCharsets.UTF_8).strip();
        } catch (IOException e) {
            // The agent has nothing to say without these. Failing to start is the honest
            // outcome; coming up with an empty brief would be worse.
            throw new UncheckedIOException("Cannot read " + path + " from the classpath.", e);
        }
    }
}

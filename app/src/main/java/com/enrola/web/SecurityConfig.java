package com.enrola.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.util.StringUtils;

/**
 * A password on the admin pages, and nothing else changed.
 *
 * <p>Adding Spring Security makes every endpoint authenticated by default, so the chain below
 * has to permit {@code /api/**} explicitly -- without that line the whole application goes dark,
 * including {@code chat.sh}.
 */
@Configuration
// Explicit rather than relying on the auto-configuration: it is what makes HttpSecurity
// available, so importing this class into a test slice works the same as running the app.
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(
                        requests ->
                                requests
                                        .requestMatchers("/admin/**")
                                        .authenticated()
                                        .anyRequest()
                                        .permitAll())
                // The admin forms keep CSRF protection -- Thymeleaf puts the token in them for
                // us. The API cannot have it: chat.sh posts every message, and has no session to
                // carry a token in.
                .csrf(csrf -> csrf.ignoringRequestMatchers("/api/**"))
                .httpBasic(basic -> {})
                .build();
    }

    @Bean
    UserDetailsService adminUser(
            @Value("${admin.username}") String username,
            @Value("${admin.password}") String password,
            PasswordEncoder encoder) {
        if (!StringUtils.hasText(password)) {
            // These pages show every message the agent has exchanged with a real person. An
            // unset password should stop the application, not quietly open them.
            throw new IllegalStateException(
                    "No admin password. Set ADMIN_PASSWORD in the environment or .env.");
        }
        return new InMemoryUserDetailsManager(
                User.withUsername(username).password(encoder.encode(password)).roles("ADMIN").build());
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }
}

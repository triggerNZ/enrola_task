package com.enrola;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

@SpringBootApplication
public class EnrolaApplication {

    public static void main(String[] args) {
        // Close the context and forward the exit code so the process reports
        // failures to the shell instead of always exiting 0.
        ConfigurableApplicationContext context = SpringApplication.run(EnrolaApplication.class, args);
        System.exit(SpringApplication.exit(context));
    }
}

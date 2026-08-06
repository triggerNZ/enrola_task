package com.enrola;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class EnrolaApplication {

    public static void main(String[] args) {
        // Just run: for a web application `run` returns as soon as startup finishes and the
        // server keeps the JVM alive. Calling SpringApplication.exit here -- as this did
        // while the chat loop lived in an ApplicationRunner -- would stop the server the
        // moment it had started.
        SpringApplication.run(EnrolaApplication.class, args);
    }
}

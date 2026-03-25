package com.filetransfer.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Provides a BCryptPasswordEncoder bean without enabling full Spring Security.
 * We only need the crypto module for hashing share-code passwords.
 *
 * BCrypt strength=12: ~400ms per hash — good balance for occasional use.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}
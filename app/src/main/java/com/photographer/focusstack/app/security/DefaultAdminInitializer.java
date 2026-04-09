package com.photographer.focusstack.app.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.password.PasswordEncoder;

@Configuration
public class DefaultAdminInitializer {

    private static final Logger log = LoggerFactory.getLogger(DefaultAdminInitializer.class);

    @Bean
    public CommandLineRunner initDefaultAdmin(
            UserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${focusstack.security.admin-password:focusstack}") String adminPassword) {
        return args -> {
            if (userRepository.findByUsername("admin").isEmpty()) {
                userRepository.save(new UserEntity(
                        "admin",
                        passwordEncoder.encode(adminPassword),
                        UserRole.ADMIN));
                log.info("Default admin user created. Change the password via focusstack.security.admin-password");
            }
        };
    }
}

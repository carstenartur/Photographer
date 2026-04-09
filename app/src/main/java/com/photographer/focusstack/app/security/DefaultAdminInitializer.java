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
            var existing = userRepository.findByUsername("admin");
            if (existing.isEmpty()) {
                userRepository.save(new UserEntity(
                        "admin",
                        passwordEncoder.encode(adminPassword),
                        UserRole.ADMIN));
                log.info("Default admin user created. Change the password via focusstack.security.admin-password");
            } else {
                UserEntity admin = existing.get();
                if (!passwordEncoder.matches(adminPassword, admin.getPasswordHash())) {
                    admin.setPasswordHash(passwordEncoder.encode(adminPassword));
                    userRepository.save(admin);
                    log.info("Admin password updated to match configured value");
                }
            }
        };
    }
}

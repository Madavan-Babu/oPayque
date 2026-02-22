package com.opayque.api.identity.service;

import com.opayque.api.identity.entity.Role;
import com.opayque.api.identity.entity.User;
import com.opayque.api.identity.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * <p>
 * Component responsible for seeding a default system administrator account during
 * application startup. It implements {@link CommandLineRunner} so that Spring Boot
 * invokes the {@code run} method after the application context has been initialized.
 * </p>
 *
 * <p>
 * The seeder reads the administrator credentials from configuration properties
 * {@code application.security.admin.email} and {@code application.security.admin.password},
 * encodes the password using the injected {@link PasswordEncoder}, and persists a
 * {@link User} entity with role {@link Role#ADMIN}. The operation is idempotent:
 * it first checks {@link UserRepository#findByEmail(String)} and only creates the
 * account when the email is not already present, preventing duplicate entries on
 * subsequent application restarts.
 * </p>
 *
 * <p>
 * This class enables a zero‑touch deployment scenario where the initial admin
 * account is guaranteed to exist without manual database manipulation, facilitating
 * immediate access to privileged functionality for system administrators.
 * </p>
 *
 * @author Madavan Babu
 * @since 2026
 *
 * @see UserRepository
 * @see User
 * @see Role
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AdminAccountSeeder implements CommandLineRunner {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${application.security.admin.email}")
    private String adminEmail;

    @Value("${application.security.admin.password}")
    private String adminPassword;

    /**
     * <p>
     * Seeds a default system administrator account during application startup. This
     * method is invoked by Spring Boot because the containing class implements
     * {@link CommandLineRunner}. It reads the administrator credentials from the
     * configuration properties {@code application.security.admin.email} and
     * {@code application.security.admin.password}, encodes the password using the
     * injected {@link PasswordEncoder}, and persists a {@link User} entity with the
     * {@link Role#ADMIN} role.
     * </p>
     *
     * <p>
     * The operation is idempotent: it first checks {@link UserRepository#findByEmail(String)}
     * for the configured {@code adminEmail}. If no account exists, a new {@link User}
     * is created and saved; otherwise the method logs that the administrator already
     * exists and skips seeding. This guarantees that the admin account is present
     * after the first deployment without creating duplicate rows on subsequent
     * restarts.
     * </p>
     *
     * @param args command‑line arguments passed to the application (not used by this seeder)
     *
     * @see UserRepository
     * @see User
     * @see Role
     * @see CommandLineRunner
     * @see PasswordEncoder
     */
    @Override
    @Transactional
    public void run(String... args) {
        // Idempotency Check: Only seed if the specific admin email is missing
        if (userRepository.findByEmail(adminEmail).isEmpty()) {
            log.warn("System Initialization: Seeding default Administrator account for [{}]", adminEmail);

            User admin = User.builder()
                    .email(adminEmail)
                    .password(passwordEncoder.encode(adminPassword))
                    .fullName("System Administrator")
                    .role(Role.ADMIN)
                    .build();

            userRepository.save(admin);
            log.info("Administrator account seeded successfully.");
        } else {
            log.debug("Administrator account [{}] already exists. Skipping seed.", adminEmail);
        }
    }
}
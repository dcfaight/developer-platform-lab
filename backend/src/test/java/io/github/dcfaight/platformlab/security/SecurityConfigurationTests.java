package io.github.dcfaight.platformlab.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.github.dcfaight.platformlab.config.ApiException;
import io.github.dcfaight.platformlab.environment.EnvironmentController;
import io.github.dcfaight.platformlab.environment.EnvironmentService;
import io.github.dcfaight.platformlab.security.PlatformSecurityProperties.Role;
import io.github.dcfaight.platformlab.security.PlatformSecurityProperties.User;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.TestingAuthenticationToken;

class SecurityConfigurationTests {
    private static final String BCRYPT = "{bcrypt}$2a$04$abcdefghijklmnopqrstuuJ1yR2Jx7rG0QxH0oQzW1b1aN2y7m3Ce";
    // Generated with the Python standard-library recipe documented in backend/README.md.
    private static final String PYTHON_PBKDF2 = "{pbkdf2@SpringSecurity_v5_8}000102030405060708090a0b0c0d0e0f"
            + "b2aaca0d7f57d76ff55cda3fa5885427c38ab29a98ea36aaff4b0f7aecf591a5";

    @Test
    void documentedPythonPbkdf2RecipeAndBcryptHashesVerify() {
        var encoder = new SecurityConfiguration().passwordEncoder();
        assertTrue(encoder.matches("documented-recipe-check", PYTHON_PBKDF2));
        assertFalse(encoder.matches("documented-recipe-check!", PYTHON_PBKDF2));
        String bcrypt = encoder.encode("bcrypt-check");
        assertTrue(bcrypt.startsWith("{bcrypt}$2"));
        assertTrue(encoder.matches("bcrypt-check", bcrypt));
        assertThrows(IllegalArgumentException.class, () -> encoder.matches("plain", "{noop}plain"));
    }

    @Test
    void acceptsValidAccountsAndTreatsMissingConfigurationAsNoAccounts() {
        assertEquals(List.of(), new PlatformSecurityProperties(null).users());
        var properties = new PlatformSecurityProperties(List.of(
                new User("alice", BCRYPT, Set.of(Role.OPERATOR)),
                new User("cleanup.bot@local", PYTHON_PBKDF2, Set.of(Role.CLEANUP)),
                new User("developer", BCRYPT, Set.of(Role.DEVELOPER)),
                new User("admin", BCRYPT, Set.of(Role.DEVELOPER, Role.OPERATOR))));
        assertEquals(4, properties.users().size());
        assertFalse(properties.users().get(0).toString().contains("$2a$"), "Hashes must not appear in logs");
    }

    @Test
    void rejectsUnsafeOrAmbiguousAccountConfiguration() {
        for (User user : new User[] {
                new User(null, BCRYPT, Set.of(Role.OPERATOR)),
                new User("", BCRYPT, Set.of(Role.OPERATOR)),
                new User("-alice", BCRYPT, Set.of(Role.OPERATOR)),
                new User("alice:admin", BCRYPT, Set.of(Role.OPERATOR)),
                new User("a".repeat(65), BCRYPT, Set.of(Role.OPERATOR)),
                new User("Local-Development", BCRYPT, Set.of(Role.OPERATOR)),
                new User("alice", null, Set.of(Role.OPERATOR)),
                new User("alice", "plaintext-password", Set.of(Role.OPERATOR)),
                new User("alice", "{noop}plaintext-password", Set.of(Role.OPERATOR)),
                new User("alice", "{sha256}abc", Set.of(Role.OPERATOR)),
                new User("alice", BCRYPT, Set.of()),
                new User("alice", BCRYPT, null),
                new User("alice", BCRYPT, Set.of(Role.CLEANUP, Role.OPERATOR)),
                new User("alice", BCRYPT, Set.of(Role.CLEANUP, Role.DEVELOPER)),
                new User("alice", BCRYPT, Set.of(Role.CLEANUP, Role.DEVELOPER, Role.OPERATOR))}) {
            var failure = assertThrows(IllegalStateException.class,
                    () -> new PlatformSecurityProperties(List.of(user)), user.toString());
            assertFalse(failure.getMessage().contains("plaintext-password"));
        }
        assertThrows(IllegalStateException.class, () -> new PlatformSecurityProperties(List.of(
                new User("alice", BCRYPT, Set.of(Role.OPERATOR)),
                new User("ALICE", BCRYPT, Set.of(Role.CLEANUP)))));
    }

    @Test
    void controllerRefusesUnguardedDeletionForNonOperatorsBeforeTheService() {
        var service = mock(EnvironmentService.class);
        var controller = new EnvironmentController(service);
        var cleanup = new TestingAuthenticationToken("cleanup-bot", null, "ROLE_CLEANUP");
        var failure = assertThrows(ApiException.class,
                () -> controller.delete(UUID.randomUUID().toString(), null, cleanup));
        assertEquals(HttpStatus.FORBIDDEN, failure.status());
        assertEquals("FORBIDDEN", failure.code());
        verifyNoInteractions(service);
    }
}

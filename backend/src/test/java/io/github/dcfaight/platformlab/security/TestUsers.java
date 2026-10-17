package io.github.dcfaight.platformlab.security;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;

/** Test-only accounts. Low bcrypt cost keeps integration tests fast; never use it in real configuration. */
public final class TestUsers {
    public static final String OPERATOR = "alice";
    public static final String SECOND_OPERATOR = "bob";
    public static final String CLEANUP = "cleanup-bot";
    public static final String DEVELOPER = "dev-a";
    public static final String TEAMMATE = "dev-peer";
    public static final String OTHER_DEVELOPER = "dev-b";
    private static final Map<String, String> ROLES = Map.of(
            OPERATOR, "OPERATOR", SECOND_OPERATOR, "OPERATOR", CLEANUP, "CLEANUP",
            DEVELOPER, "DEVELOPER", TEAMMATE, "DEVELOPER", OTHER_DEVELOPER, "DEVELOPER");
    private static final List<String> NAMES = List.of(OPERATOR, SECOND_OPERATOR, CLEANUP,
            DEVELOPER, TEAMMATE, OTHER_DEVELOPER);
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder(4);
    private static final Map<String, String> HASHES = Map.of(
            OPERATOR, "{bcrypt}" + ENCODER.encode(password(OPERATOR)),
            SECOND_OPERATOR, "{bcrypt}" + ENCODER.encode(password(SECOND_OPERATOR)),
            CLEANUP, "{bcrypt}" + ENCODER.encode(password(CLEANUP)),
            DEVELOPER, "{bcrypt}" + ENCODER.encode(password(DEVELOPER)),
            TEAMMATE, "{bcrypt}" + ENCODER.encode(password(TEAMMATE)),
            OTHER_DEVELOPER, "{bcrypt}" + ENCODER.encode(password(OTHER_DEVELOPER)));

    private TestUsers() {
    }

    public static String password(String username) {
        return "test-only-" + username + "-pass";
    }

    public static String basic(String username) {
        return basic(username, password(username));
    }

    public static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    public static void register(DynamicPropertyRegistry registry) {
        for (int index = 0; index < NAMES.size(); index++) {
            String name = NAMES.get(index);
            registry.add("platform.security.users[" + index + "].username", () -> name);
            registry.add("platform.security.users[" + index + "].password-hash", () -> HASHES.get(name));
            registry.add("platform.security.users[" + index + "].roles", () -> ROLES.get(name));
        }
    }

    public static List<String> commandLineArguments() {
        List<String> arguments = new ArrayList<>();
        for (int index = 0; index < NAMES.size(); index++) {
            String name = NAMES.get(index);
            arguments.add("--platform.security.users[" + index + "].username=" + name);
            arguments.add("--platform.security.users[" + index + "].password-hash=" + HASHES.get(name));
            arguments.add("--platform.security.users[" + index + "].roles=" + ROLES.get(name));
        }
        return arguments;
    }
}

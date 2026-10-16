package io.github.dcfaight.platformlab.security;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Locally configured API accounts. Passwords are stored only as Spring Security
 * {@code {bcrypt}} or {@code {pbkdf2@SpringSecurity_v5_8}} hashes; plaintext and
 * {@code {noop}} values are rejected at startup.
 */
@ConfigurationProperties("platform.security")
public record PlatformSecurityProperties(List<User> users) {
    static final Pattern USERNAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._@-]{0,63}");
    static final String LEGACY_ACTOR = "local-development";
    static final String BCRYPT = "bcrypt";
    static final String PBKDF2 = "pbkdf2@SpringSecurity_v5_8";

    public PlatformSecurityProperties {
        users = users == null ? List.of() : List.copyOf(users);
        Set<String> names = new HashSet<>();
        for (int index = 0; index < users.size(); index++) {
            User user = users.get(index);
            String prefix = "platform.security.users[" + index + "]";
            if (user == null || user.username() == null || !USERNAME.matcher(user.username()).matches()) {
                throw new IllegalStateException(prefix + ".username must be 1 to 64 characters: letters, digits, "
                        + "'.', '_', '@', '-' and start with a letter or digit.");
            }
            if (LEGACY_ACTOR.equalsIgnoreCase(user.username())) {
                throw new IllegalStateException(prefix + ".username is reserved for legacy audit records.");
            }
            if (!names.add(user.username().toLowerCase(Locale.ROOT))) {
                throw new IllegalStateException(prefix + ".username duplicates another configured user.");
            }
            String hash = user.passwordHash();
            if (hash == null || !(hash.startsWith("{" + BCRYPT + "}") || hash.startsWith("{" + PBKDF2 + "}"))) {
                throw new IllegalStateException(prefix + ".password-hash must be a {" + BCRYPT + "} or {"
                        + PBKDF2 + "} hash; plaintext and {noop} values are not accepted.");
            }
            if (user.roles().isEmpty()) {
                throw new IllegalStateException(prefix + ".roles must contain OPERATOR and/or CLEANUP.");
            }
        }
    }

    public record User(String username, String passwordHash, Set<Role> roles) {
        public User {
            roles = roles == null ? Set.of() : Set.copyOf(roles);
        }

        @Override
        public String toString() {
            return "User[username=" + username + ", roles=" + roles + "]";
        }
    }

    public enum Role {
        /** May create environments and request manual or expiry-guarded deletion. */
        OPERATOR,
        /** May only request expiry-guarded deletion ({@code DELETE ...?expiredBefore=}). */
        CLEANUP
    }
}

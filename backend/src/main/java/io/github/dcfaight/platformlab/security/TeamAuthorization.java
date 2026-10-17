package io.github.dcfaight.platformlab.security;

import io.github.dcfaight.platformlab.config.ApiException;
import io.github.dcfaight.platformlab.environment.EnvironmentRepository.EnvironmentRecord;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

@Service
public class TeamAuthorization {
    private final JdbcTemplate jdbc;

    public TeamAuthorization(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean hasRole(Authentication authentication, String role) {
        return authentication != null && authentication.isAuthenticated()
                && authentication.getAuthorities().stream()
                        .anyMatch(authority -> ("ROLE_" + role).equals(authority.getAuthority()));
    }

    public List<String> teams(Authentication authentication) {
        requireAccount(authentication);
        return jdbc.queryForList("SELECT team_id FROM account_team_memberships WHERE username = ? ORDER BY team_id",
                String.class, authentication.getName());
    }

    public String createTeam(Authentication authentication, String team) {
        requireAccount(authentication);
        if (hasRole(authentication, "CLEANUP")) {
            throw forbidden();
        }
        if (hasRole(authentication, "OPERATOR")) {
            if (team != null && !Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT EXISTS (SELECT 1 FROM platform_teams WHERE id = ?)", Boolean.class, team))) {
                throw forbidden();
            }
        } else if (!hasRole(authentication, "DEVELOPER") || team == null || !teams(authentication).contains(team)) {
            throw forbidden();
        }
        return team;
    }

    public void read(Authentication authentication, EnvironmentRecord environment) {
        requireAccount(authentication);
        if (hasRole(authentication, "CLEANUP")) {
            throw forbidden();
        }
        if (!hasRole(authentication, "OPERATOR")
                && !(hasRole(authentication, "DEVELOPER") && environment.teamId() != null
                        && teams(authentication).contains(environment.teamId()))) {
            throw forbidden();
        }
    }

    public void delete(Authentication authentication, EnvironmentRecord environment, Instant cutoff) {
        requireAccount(authentication);
        if (hasRole(authentication, "CLEANUP")) {
            if (cutoff == null) {
                throw forbidden();
            }
        } else {
            read(authentication, environment);
        }
    }

    public void requireAccount(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(hasRole(authentication, "OPERATOR") || hasRole(authentication, "DEVELOPER")
                        || hasRole(authentication, "CLEANUP"))) {
            throw forbidden();
        }
    }

    private static ApiException forbidden() {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                "The authenticated user is not permitted to perform this operation.");
    }
}

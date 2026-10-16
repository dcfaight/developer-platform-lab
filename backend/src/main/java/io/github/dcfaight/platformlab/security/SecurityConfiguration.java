package io.github.dcfaight.platformlab.security;

import io.github.dcfaight.platformlab.config.ApiErrorHandler.ApiError;
import io.github.dcfaight.platformlab.config.RequestCorrelationFilter;
import io.github.dcfaight.platformlab.security.PlatformSecurityProperties.Role;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import tools.jackson.databind.ObjectMapper;

/**
 * First authentication/authorization boundary: stateless HTTP Basic against locally
 * configured, hashed accounts. Mutations require a role; reads stay trusted-local.
 */
@Configuration
@EnableConfigurationProperties(PlatformSecurityProperties.class)
public class SecurityConfiguration {
    static final String EXPIRED_BEFORE = "expiredBefore";
    private static final Logger LOG = LoggerFactory.getLogger(SecurityConfiguration.class);

    @Bean
    SecurityFilterChain apiSecurity(HttpSecurity http, ObjectMapper mapper) throws Exception {
        var paths = PathPatternRequestMatcher.withDefaults();
        RequestMatcher deleteEnvironment = paths.matcher(HttpMethod.DELETE, "/api/v1/environments/*");
        // The controller reads the same request parameter, so a request matched here
        // is always processed with the server-side expiry and READY/FAILED guard.
        RequestMatcher guardedDelete = new AndRequestMatcher(deleteEnvironment,
                request -> request.getParameter(EXPIRED_BEFORE) != null);
        AuthenticationEntryPoint unauthenticated = (request, response, exception) -> write(mapper, request,
                response, HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Valid credentials are required for this operation.");
        AccessDeniedHandler forbidden = (request, response, exception) -> write(mapper, request, response,
                HttpStatus.FORBIDDEN, "FORBIDDEN", "The authenticated user is not permitted to perform this operation.");
        // CSRF protection is unnecessary: no session or cookie exists, credentials are only
        // sent as an explicit Authorization header, and 401 omits WWW-Authenticate so browsers
        // never cache Basic credentials to attach ambiently to cross-site requests.
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.authenticationEntryPoint(unauthenticated))
                .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(unauthenticated)
                        .accessDeniedHandler(forbidden))
                .authorizeHttpRequests(requests -> requests
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        .requestMatchers(paths.matcher(HttpMethod.GET, "/api/v1/me")).authenticated()
                        .requestMatchers(paths.matcher(HttpMethod.POST, "/api/v1/environments"))
                        .hasRole(Role.OPERATOR.name())
                        .requestMatchers(guardedDelete).hasAnyRole(Role.OPERATOR.name(), Role.CLEANUP.name())
                        .requestMatchers(deleteEnvironment).hasRole(Role.OPERATOR.name())
                        .requestMatchers(paths.matcher(HttpMethod.GET, "/api/v1/**")).permitAll()
                        .requestMatchers(paths.matcher(HttpMethod.HEAD, "/api/v1/**")).permitAll()
                        // Fail closed: any other method or path requires the strongest role.
                        .anyRequest().hasRole(Role.OPERATOR.name()))
                .build();
    }

    @Bean
    UserDetailsService users(PlatformSecurityProperties properties) {
        if (properties.users().isEmpty()) {
            LOG.warn("No platform.security.users are configured; all create and delete requests will be rejected.");
        }
        return new InMemoryUserDetailsManager(properties.users().stream().map(user -> User
                .withUsername(user.username())
                .password(user.passwordHash())
                .roles(user.roles().stream().map(Role::name).toArray(String[]::new))
                .build()).toList());
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // Only explicit, salted, slow hash formats are accepted; there is no plaintext fallback.
        return new DelegatingPasswordEncoder(PlatformSecurityProperties.BCRYPT, Map.of(
                PlatformSecurityProperties.BCRYPT, new BCryptPasswordEncoder(),
                PlatformSecurityProperties.PBKDF2, Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }

    private static void write(ObjectMapper mapper, HttpServletRequest request, HttpServletResponse response,
            HttpStatus status, String code, String message) throws IOException {
        // Deliberately no WWW-Authenticate challenge: browsers must never prompt for
        // and cache ambient Basic credentials that cross-site requests could reuse.
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        mapper.writeValue(response.getOutputStream(), new ApiError(code, message,
                (String) request.getAttribute(RequestCorrelationFilter.REQUEST_ID), List.of()));
    }
}

package io.github.dcfaight.platformlab.worker;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.worker")
public record WorkerProperties(Duration leaseDuration, int maxAttempts, Duration retryBackoff,
        Duration readinessPollInterval, Duration readinessTimeout) {
    public WorkerProperties {
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()
                || maxAttempts < 1 || retryBackoff == null || retryBackoff.isNegative() || retryBackoff.isZero()
                || readinessPollInterval == null || readinessPollInterval.isNegative() || readinessPollInterval.isZero()
                || readinessTimeout == null || readinessTimeout.isNegative() || readinessTimeout.isZero()) {
            throw new IllegalArgumentException("Worker durations and max-attempts must be positive.");
        }
    }
}

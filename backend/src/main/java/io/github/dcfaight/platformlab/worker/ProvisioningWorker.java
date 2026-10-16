package io.github.dcfaight.platformlab.worker;

import io.github.dcfaight.platformlab.environment.EnvironmentRepository;
import io.github.dcfaight.platformlab.kubernetes.EnvironmentProvisioner;
import io.github.dcfaight.platformlab.kubernetes.ProvisioningException;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@EnableConfigurationProperties(WorkerProperties.class)
@ConditionalOnProperty(name = "platform.worker.enabled", havingValue = "true")
public class ProvisioningWorker {
    private static final Logger LOG = LoggerFactory.getLogger(ProvisioningWorker.class);
    private final WorkerRepository operations;
    private final EnvironmentRepository environments;
    private final EnvironmentProvisioner provisioner;
    private final WorkerProperties properties;

    public ProvisioningWorker(WorkerRepository operations, EnvironmentRepository environments,
            EnvironmentProvisioner provisioner, WorkerProperties properties) {
        this.operations = operations;
        this.environments = environments;
        this.provisioner = provisioner;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${platform.worker.poll-delay-ms:1000}")
    public void poll() {
        operations.claim(properties).ifPresent(claim -> {
            if (!"RUNNING".equals(claim.status())) {
                return;
            }
            try {
                var environment = environments.find(claim.environmentId()).orElseThrow();
                if (!claim.provisioned()) {
                    provisioner.provision(environment);
                    if (!operations.provisioned(claim)) {
                        return;
                    }
                }
                if (provisioner.isReady(environment)) {
                    operations.succeed(claim);
                } else {
                    operations.waiting(claim, properties.readinessPollInterval());
                }
            } catch (ProvisioningException failure) {
                if (failure.retryable() && claim.failureCount() + 1 < properties.maxAttempts()) {
                    long base = Math.min(properties.retryBackoff().toMillis(), 60_000);
                    long multiplier = 1L << Math.min(claim.failureCount(), 16);
                    operations.retry(claim, failure.code(), failure.getMessage(),
                            Duration.ofMillis(Math.min(base * multiplier, 60_000)));
                } else {
                    operations.fail(claim, failure.code(), failure.getMessage());
                }
            } catch (Exception failure) {
                operations.fail(claim, "INTERNAL_ERROR", "An internal provisioning error occurred.");
                // Never log exception messages, which may contain credentials or Kubernetes response bodies.
                LOG.error("Unexpected provisioning failure operationId={} exceptionType={}",
                        claim.id(), failure.getClass().getName());
                throw new IllegalStateException("Internal provisioning failure for operation " + claim.id());
            }
        });
    }
}

package io.github.dcfaight.platformlab.worker;

import io.github.dcfaight.platformlab.environment.EnvironmentRepository;
import io.github.dcfaight.platformlab.kubernetes.EnvironmentProvisioner;
import io.github.dcfaight.platformlab.kubernetes.ProvisioningException;
import io.github.dcfaight.platformlab.operation.OperationMilestone;
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
                if (claim.deleting()) {
                    if (!claim.provisioned() && !operations.progress(claim, OperationMilestone.DELETE_STARTED)) {
                        return;
                    }
                    if (!operations.owns(claim)) {
                        return;
                    }
                    if (provisioner.delete(environment, claim.createOperationId(), () -> {
                        if (!operations.owns(claim)) {
                            return false;
                        }
                        // Reclaims must observe accepted deletion/absence before enforcing crash exhaustion.
                        if (!claim.provisioned() && claim.attemptCount() > properties.maxAttempts()) {
                            throw new ProvisioningException("ATTEMPTS_EXHAUSTED",
                                    "Deletion did not complete within its attempt limits.", false);
                        }
                        return true;
                    })) {
                        operations.succeed(claim);
                    } else {
                        operations.deletionWaiting(claim, properties.readinessPollInterval());
                    }
                    return;
                }
                if (!claim.provisioned()) {
                    if (!operations.progress(claim, OperationMilestone.PROVISION_STARTED)) {
                        return;
                    }
                    provisioner.provision(environment, claim.id(), () -> {
                        if (!operations.owns(claim)) {
                            return false;
                        }
                        // Observe resources accepted before a crash before enforcing the write budget.
                        if (claim.attemptCount() > properties.maxAttempts()) {
                            throw new ProvisioningException("ATTEMPTS_EXHAUSTED",
                                    "Provisioning did not complete within its attempt limits.", false);
                        }
                        return true;
                    }, milestone -> operations.progress(claim, milestone));
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
                operations.fail(claim, "INTERNAL_ERROR", claim.deleting()
                        ? "An internal deletion error occurred." : "An internal provisioning error occurred.");
                // Never log exception messages, which may contain credentials or Kubernetes response bodies.
                LOG.error("Unexpected environment operation failure operationId={} exceptionType={}",
                        claim.id(), failure.getClass().getName());
                throw new IllegalStateException("Internal environment operation failure for operation " + claim.id());
            }
        });
    }
}

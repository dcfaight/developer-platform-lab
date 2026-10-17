package io.github.dcfaight.platformlab.kubernetes;

import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.DeleteOptionsBuilder;
import io.fabric8.kubernetes.api.model.LimitRange;
import io.fabric8.kubernetes.api.model.LimitRangeBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.github.dcfaight.platformlab.environment.EnvironmentRepository.EnvironmentRecord;
import io.github.dcfaight.platformlab.kubernetes.OperationProgress.Milestone;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "platform.worker.enabled", havingValue = "true")
public class KubernetesEnvironmentProvisioner implements EnvironmentProvisioner, AutoCloseable {
    static final String MANAGED_BY = "app.kubernetes.io/managed-by";
    static final String ENVIRONMENT_ID = "platform-lab/environment-id";
    static final String CREATE_OPERATION_ID = "platform-lab/create-operation-id";
    static final String DEPLOYMENT = "sample-service";
    static final String IMAGE = "nginxinc/nginx-unprivileged:1.28-alpine";
    static final String RESOURCE_QUOTA = "platform-environment-budget";
    static final String LIMIT_RANGE = "platform-container-defaults";
    private final KubernetesClient client;

    @Autowired
    public KubernetesEnvironmentProvisioner(
            @Value("${platform.kubernetes.mode:local-kind}") String mode,
            @Value("${platform.kubernetes.kubeconfig:${user.home}/.kube/config}") String kubeconfig) {
        this(WorkerKubernetesClient.open(mode, kubeconfig));
    }

    KubernetesEnvironmentProvisioner(KubernetesClient client) {
        this.client = client;
    }

    @Override
    public void provision(EnvironmentRecord environment) {
        provision(environment, null);
    }

    @Override
    public void provision(EnvironmentRecord environment, UUID createOperationId) {
        provision(environment, createOperationId, () -> true);
    }

    @Override
    public void provision(EnvironmentRecord environment, UUID createOperationId, OperationProgress leaseValid) {
        execute(() -> {
            validate(environment);
            Namespace namespace = client.namespaces().withName(environment.namespace()).get();
            if (namespace == null) {
                var labels = new java.util.HashMap<>(ownership(environment));
                if (createOperationId != null) {
                    labels.put(CREATE_OPERATION_ID, createOperationId.toString());
                }
                if (!leaseValid.getAsBoolean()) {
                    return null;
                }
                namespace = client.namespaces().resource(new NamespaceBuilder()
                        .withNewMetadata().withName(environment.namespace())
                        .withLabels(labels).withAnnotations(labels)
                        .endMetadata().build()).create();
                leaseValid.milestone(Milestone.NAMESPACE_CREATED);
            }
            requireOwned(namespace, environment);
            if (createOperationId != null) {
                requireCreateOwnerIfPresent(namespace, createOperationId);
            }
            if (!reconcileResourceQuota(environment, leaseValid)
                    || !reconcileLimitRange(environment, leaseValid)) {
                return null;
            }
            Deployment existing = client.apps().deployments().inNamespace(environment.namespace())
                    .withName(DEPLOYMENT).get();
            Deployment desired = deployment(environment);
            if (existing == null) {
                if (!leaseValid.getAsBoolean()) {
                    return null;
                }
                client.apps().deployments().inNamespace(environment.namespace()).resource(desired).create();
                leaseValid.milestone(Milestone.WORKLOAD_CREATED);
            } else {
                requireOwned(existing, environment);
                if (!desired.getSpec().equals(existing.getSpec())) {
                    // Preserve metadata and lock the exact resource read; a race must never adopt a replacement.
                    desired.setMetadata(existing.getMetadata());
                    if (!leaseValid.getAsBoolean()) {
                        return null;
                    }
                    client.apps().deployments().inNamespace(environment.namespace()).resource(desired)
                            .lockResourceVersion(existing.getMetadata().getResourceVersion()).replace();
                }
            }
            return null;
        });
    }

    @Override
    public boolean delete(EnvironmentRecord environment, UUID createOperationId, OperationProgress leaseValid) {
        return execute(() -> {
            validate(environment);
            if (createOperationId == null) {
                throw new ProvisioningException("KUBERNETES_OWNERSHIP_CONFLICT",
                        "The environment create operation is missing.", false);
            }
            Namespace namespace = client.namespaces().withName(environment.namespace()).get();
            if (namespace == null) {
                return true;
            }
            var metadata = namespace.getMetadata();
            if (metadata == null || !environment.namespace().equals(metadata.getName())
                    || !contains(metadata.getLabels(), ownership(environment))
                    || !contains(metadata.getAnnotations(), ownership(environment))) {
                throw new ProvisioningException("KUBERNETES_OWNERSHIP_CONFLICT",
                        "The Kubernetes namespace is not owned by this environment.", false);
            }
            requireCreateOwnerIfPresent(namespace, createOperationId);
            if (metadata.getDeletionTimestamp() != null || (namespace.getStatus() != null
                    && "Terminating".equals(namespace.getStatus().getPhase()))) {
                return false;
            }
            if (metadata.getUid() == null || metadata.getUid().isBlank()
                    || metadata.getResourceVersion() == null || metadata.getResourceVersion().isBlank()) {
                throw new ProvisioningException("KUBERNETES_VALIDATION_FAILED",
                        "The Kubernetes namespace identity is missing.", false);
            }
            if (!leaseValid.getAsBoolean()) {
                return false;
            }
            try {
                // Fabric8's typed delete DSL only supports resourceVersion, not UID preconditions.
                client.raw("/api/v1/namespaces/" + environment.namespace(), "DELETE", new DeleteOptionsBuilder()
                        .withNewPreconditions().withUid(metadata.getUid())
                            .withResourceVersion(metadata.getResourceVersion()).endPreconditions().build());
            } catch (KubernetesClientException exception) {
                if (exception.getCode() == 404) {
                    return true;
                }
                throw exception;
            }
            leaseValid.milestone(Milestone.NAMESPACE_DELETE_REQUESTED);
            // An accepted DELETE is not completion: finalizers can keep the namespace alive.
            return false;
        });
    }

    private static void requireCreateOwnerIfPresent(Namespace namespace, UUID createOperationId) {
        var metadata = namespace.getMetadata();
        // Original platform namespaces predate CREATE markers; do not relabel or adopt them.
        if (!metadata.getLabels().containsKey(CREATE_OPERATION_ID)
                && !metadata.getAnnotations().containsKey(CREATE_OPERATION_ID)) {
            return;
        }
        var expected = Map.of(CREATE_OPERATION_ID, createOperationId.toString());
        if (!contains(metadata.getLabels(), expected) || !contains(metadata.getAnnotations(), expected)) {
            throw new ProvisioningException("KUBERNETES_OWNERSHIP_CONFLICT",
                    "The namespace is not owned by the environment create operation.", false);
        }
    }

    @Override
    public boolean isReady(EnvironmentRecord environment) {
        return execute(() -> {
            validate(environment);
            Namespace namespace = client.namespaces().withName(environment.namespace()).get();
            if (namespace == null) {
                return false;
            }
            requireOwned(namespace, environment);
            Deployment deployment = client.apps().deployments().inNamespace(environment.namespace())
                    .withName(DEPLOYMENT).get();
            if (deployment == null) {
                return false;
            }
            requireOwned(deployment, environment);
            if (!deployment(environment).getSpec().equals(deployment.getSpec())) {
                return false;
            }
            var status = deployment.getStatus();
            Long generation = deployment.getMetadata().getGeneration();
            return generation != null && status != null && status.getObservedGeneration() != null
                    && status.getObservedGeneration() >= generation
                    && Integer.valueOf(1).equals(deployment.getSpec().getReplicas())
                    && Integer.valueOf(1).equals(status.getUpdatedReplicas())
                    && Integer.valueOf(1).equals(status.getAvailableReplicas())
                    && Integer.valueOf(1).equals(status.getReplicas());
        });
    }

    private static void validate(EnvironmentRecord environment) {
        if (environment == null || environment.id() == null
                || !("env-" + environment.id().toString().replace("-", "")).equals(environment.namespace())) {
            throw new ProvisioningException("KUBERNETES_VALIDATION_FAILED",
                    "The persisted environment namespace is invalid.", false);
        }
    }

    private static Map<String, String> ownership(EnvironmentRecord environment) {
        return Map.of(MANAGED_BY, "platform-lab", ENVIRONMENT_ID, environment.id().toString());
    }

    private boolean reconcileResourceQuota(EnvironmentRecord environment, OperationProgress leaseValid) {
        var operation = client.resourceQuotas().inNamespace(environment.namespace()).withName(RESOURCE_QUOTA);
        ResourceQuota existing = operation.get();
        ResourceQuota desired = resourceQuota(environment);
        if (existing == null) {
            if (!leaseValid.getAsBoolean()) {
                return false;
            }
            client.resourceQuotas().inNamespace(environment.namespace()).resource(desired).create();
        } else {
            requireOwned(existing, environment);
            if (!desired.getSpec().equals(existing.getSpec())) {
                requireResourceVersion(existing);
                desired.setMetadata(existing.getMetadata());
                if (!leaseValid.getAsBoolean()) {
                    return false;
                }
                client.resourceQuotas().inNamespace(environment.namespace()).resource(desired)
                        .lockResourceVersion(existing.getMetadata().getResourceVersion()).replace();
            }
        }
        return true;
    }

    private boolean reconcileLimitRange(EnvironmentRecord environment, OperationProgress leaseValid) {
        var operation = client.limitRanges().inNamespace(environment.namespace()).withName(LIMIT_RANGE);
        LimitRange existing = operation.get();
        LimitRange desired = limitRange(environment);
        if (existing == null) {
            if (!leaseValid.getAsBoolean()) {
                return false;
            }
            client.limitRanges().inNamespace(environment.namespace()).resource(desired).create();
        } else {
            requireOwned(existing, environment);
            if (!desired.getSpec().equals(existing.getSpec())) {
                requireResourceVersion(existing);
                desired.setMetadata(existing.getMetadata());
                if (!leaseValid.getAsBoolean()) {
                    return false;
                }
                client.limitRanges().inNamespace(environment.namespace()).resource(desired)
                        .lockResourceVersion(existing.getMetadata().getResourceVersion()).replace();
            }
        }
        return true;
    }

    private static void requireResourceVersion(HasMetadata resource) {
        var metadata = resource.getMetadata();
        if (metadata.getResourceVersion() == null || metadata.getResourceVersion().isBlank()) {
            throw new ProvisioningException("KUBERNETES_VALIDATION_FAILED",
                    "The Kubernetes resource identity is missing.", false);
        }
    }

    static ResourceQuota resourceQuota(EnvironmentRecord environment) {
        Map<String, String> labels = ownership(environment);
        return new ResourceQuotaBuilder()
                .withNewMetadata().withName(RESOURCE_QUOTA).withNamespace(environment.namespace())
                    .withLabels(labels).withAnnotations(labels).endMetadata()
                .withNewSpec().withHard(Map.of(
                        "requests.cpu", new Quantity("2"),
                        "requests.memory", new Quantity("2Gi"),
                        "limits.cpu", new Quantity("4"),
                        "limits.memory", new Quantity("4Gi"),
                        "pods", new Quantity("10")))
                .endSpec().build();
    }

    static LimitRange limitRange(EnvironmentRecord environment) {
        Map<String, String> labels = ownership(environment);
        return new LimitRangeBuilder()
                .withNewMetadata().withName(LIMIT_RANGE).withNamespace(environment.namespace())
                    .withLabels(labels).withAnnotations(labels).endMetadata()
                .withNewSpec().addNewLimit().withType("Container")
                    .withDefault(Map.of("cpu", new Quantity("500m"), "memory", new Quantity("256Mi")))
                    .withDefaultRequest(Map.of("cpu", new Quantity("100m"), "memory", new Quantity("64Mi")))
                    .withMax(Map.of("cpu", new Quantity("1"), "memory", new Quantity("1Gi")))
                .endLimit().endSpec().build();
    }

    private static void requireOwned(HasMetadata resource, EnvironmentRecord environment) {
        var metadata = resource.getMetadata();
        if (metadata == null || metadata.getDeletionTimestamp() != null
                || !contains(metadata.getLabels(), ownership(environment))
                || !contains(metadata.getAnnotations(), ownership(environment))
                || (resource instanceof Namespace namespace && namespace.getStatus() != null
                    && "Terminating".equals(namespace.getStatus().getPhase()))) {
            throw new ProvisioningException("KUBERNETES_OWNERSHIP_CONFLICT",
                    "The Kubernetes resource is not an active owned environment resource.", false);
        }
    }

    private static boolean contains(Map<String, String> actual, Map<String, String> expected) {
        return actual != null && actual.entrySet().containsAll(expected.entrySet());
    }

    static Deployment deployment(EnvironmentRecord environment) {
        Map<String, String> labels = ownership(environment);
        return new DeploymentBuilder()
                .withNewMetadata().withName(DEPLOYMENT).withNamespace(environment.namespace())
                    .withLabels(labels).withAnnotations(labels).endMetadata()
                .withNewSpec().withReplicas(1).withProgressDeadlineSeconds(600).withRevisionHistoryLimit(10)
                    .withNewStrategy().withType("RollingUpdate")
                        .withNewRollingUpdate().withNewMaxSurge("25%").withNewMaxUnavailable("25%")
                        .endRollingUpdate().endStrategy()
                    .withNewSelector().withMatchLabels(labels).endSelector()
                    .withNewTemplate().withNewMetadata().withLabels(labels).endMetadata()
                        .withNewSpec().withAutomountServiceAccountToken(false)
                            .withDnsPolicy("ClusterFirst").withRestartPolicy("Always")
                            .withSchedulerName("default-scheduler").withTerminationGracePeriodSeconds(30L)
                            .withNewSecurityContext().withRunAsNonRoot(true).withRunAsUser(101L)
                                .withRunAsGroup(101L).withFsGroup(101L)
                                .withNewSeccompProfile().withType("RuntimeDefault").endSeccompProfile()
                            .endSecurityContext()
                            .addNewContainer().withName(DEPLOYMENT).withImage(IMAGE)
                                .withImagePullPolicy("IfNotPresent")
                                .withTerminationMessagePath("/dev/termination-log").withTerminationMessagePolicy("File")
                                .addNewPort().withContainerPort(8080).withProtocol("TCP").endPort()
                                .withNewReadinessProbe().withNewHttpGet().withPath("/")
                                    .withNewPort(8080).withScheme("HTTP").endHttpGet().withPeriodSeconds(5)
                                    .withFailureThreshold(3).withSuccessThreshold(1).withTimeoutSeconds(1)
                                .endReadinessProbe()
                                .withNewLivenessProbe().withNewHttpGet().withPath("/")
                                    .withNewPort(8080).withScheme("HTTP").endHttpGet().withInitialDelaySeconds(10)
                                    .withPeriodSeconds(10).withFailureThreshold(3)
                                    .withSuccessThreshold(1).withTimeoutSeconds(1).endLivenessProbe()
                                .withNewResources()
                                    .withRequests(Map.of("cpu", new Quantity("50m"), "memory", new Quantity("32Mi")))
                                    .withLimits(Map.of("cpu", new Quantity("250m"), "memory", new Quantity("128Mi")))
                                .endResources()
                                .withNewSecurityContext().withAllowPrivilegeEscalation(false)
                                    .withReadOnlyRootFilesystem(true)
                                    .withNewCapabilities().withDrop("ALL").endCapabilities()
                                .endSecurityContext()
                                .addNewVolumeMount().withName("tmp").withMountPath("/tmp").endVolumeMount()
                                .addNewVolumeMount().withName("cache").withMountPath("/var/cache/nginx").endVolumeMount()
                            .endContainer()
                            .addNewVolume().withName("tmp").withNewEmptyDir().endEmptyDir().endVolume()
                            .addNewVolume().withName("cache").withNewEmptyDir().endEmptyDir().endVolume()
                        .endSpec()
                    .endTemplate()
                .endSpec().build();
    }

    private <T> T execute(Supplier<T> operation) {
        try {
            return operation.get();
        } catch (ProvisioningException exception) {
            throw exception;
        } catch (KubernetesClientException exception) {
            int status = exception.getCode();
            if (status == 401) {
                throw new ProvisioningException("KUBERNETES_AUTHENTICATION_FAILED",
                        "The Kubernetes API did not accept the worker credentials.", false);
            }
            if (status == 403) {
                throw new ProvisioningException("KUBERNETES_FORBIDDEN",
                        "The worker does not have permission to access the required Kubernetes resources.", false);
            }
            boolean retryable = status == 409 || status == 429 || status >= 500
                    || (status <= 0 && causedByIo(exception));
            throw new ProvisioningException(retryable ? "KUBERNETES_UNAVAILABLE" : "KUBERNETES_REQUEST_REJECTED",
                    retryable ? "The Kubernetes API is temporarily unavailable."
                            : "The Kubernetes API rejected the environment request.", retryable);
        }
    }

    private static boolean causedByIo(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.io.IOException) {
                return true;
            }
        }
        return false;
    }

    @Override
    @PreDestroy
    public void close() {
        client.close();
    }
}

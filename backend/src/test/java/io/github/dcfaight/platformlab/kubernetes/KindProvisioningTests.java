package io.github.dcfaight.platformlab.kubernetes;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "PLATFORM_KIND_TEST", matches = "true")
class KindProvisioningTests {
    @Test
    void deletesOwnedNamespaceAndWaitsForActualAbsence() throws InterruptedException {
        deleteNamespace(false);
    }

    @Test
    void deletesLegacyOwnedNamespaceAndWaitsForActualAbsence() throws InterruptedException {
        deleteNamespace(true);
    }

    private void deleteNamespace(boolean legacy) throws InterruptedException {
        String kubeconfig = System.getProperty("platform.kubernetes.kubeconfig",
                Path.of(System.getProperty("user.home"), ".kube", "config").toString());
        var environment = KubernetesEnvironmentProvisionerTests.environment();
        var client = LocalKubernetesClient.open(kubeconfig);
        var createOperationId = java.util.UUID.randomUUID();
        try (var provisioner = new KubernetesEnvironmentProvisioner(client)) {
            if (legacy) {
                provisioner.provision(environment);
            } else {
                provisioner.provision(environment, createOperationId);
            }
            assertFalse(provisioner.delete(environment, createOperationId, () -> true));
            long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
            boolean absent;
            do {
                absent = provisioner.delete(environment, createOperationId, () -> true);
                if (!absent) {
                    Thread.sleep(500);
                }
            } while (!absent && System.nanoTime() < deadline);
            assertTrue(absent);
            assertNull(client.namespaces().withName(environment.namespace()).get());
            KindNamespaceAssertions.assertAbsent(environment.namespace());
        }
    }

    @Test
    void provisionsRerunsAndObservesRealDeploymentReadiness() throws InterruptedException {
        String kubeconfig = System.getProperty("platform.kubernetes.kubeconfig",
                Path.of(System.getProperty("user.home"), ".kube", "config").toString());
        var environment = KubernetesEnvironmentProvisionerTests.environment();
        var client = LocalKubernetesClient.open(kubeconfig);
        try (var provisioner = new KubernetesEnvironmentProvisioner(client)) {
            provisioner.provision(environment);
            var quota = client.resourceQuotas().inNamespace(environment.namespace())
                    .withName(KubernetesEnvironmentProvisioner.RESOURCE_QUOTA).get();
            var limitRange = client.limitRanges().inNamespace(environment.namespace())
                    .withName(KubernetesEnvironmentProvisioner.LIMIT_RANGE).get();
            assertNotNull(quota, "Platform ResourceQuota must exist in the provisioned namespace");
            assertNotNull(limitRange, "Platform LimitRange must exist in the provisioned namespace");
            assertEquals("2", quota.getSpec().getHard().get("requests.cpu").toString());
            assertEquals("100m", limitRange.getSpec().getLimits().get(0).getDefaultRequest().get("cpu").toString());
            Deployment initial = client.apps().deployments().inNamespace(environment.namespace())
                    .withName(KubernetesEnvironmentProvisioner.DEPLOYMENT).get();
            provisioner.provision(environment);
            Deployment rerun = client.apps().deployments().inNamespace(environment.namespace())
                    .withName(KubernetesEnvironmentProvisioner.DEPLOYMENT).get();
            assertEquals(initial.getMetadata().getGeneration(), rerun.getMetadata().getGeneration());
            long deadline = System.nanoTime() + Duration.ofSeconds(120).toNanos();
            while (!provisioner.isReady(environment) && System.nanoTime() < deadline) {
                Thread.sleep(1000);
            }
            assertTrue(provisioner.isReady(environment), readinessDiagnostics(client, environment.namespace()));
            provisioner.provision(environment);
            assertTrue(provisioner.isReady(environment));
        }
        // Retain the namespace for inspection; this integration test never deletes cluster resources.
    }

    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    void resourceQuotaRejectsAnOverBudgetPodOnDisposableKind() {
        String kubeconfig = System.getProperty("platform.kubernetes.kubeconfig",
                Path.of(System.getProperty("user.home"), ".kube", "config").toString());
        var environment = KubernetesEnvironmentProvisionerTests.environment();
        var client = LocalKubernetesClient.open(kubeconfig);
        try (var provisioner = new KubernetesEnvironmentProvisioner(client)) {
            provisioner.provision(environment);
            String podName = "over-budget-" + environment.id().toString().substring(0, 8);
            Pod pod = new PodBuilder()
                    .withNewMetadata().withName(podName).withNamespace(environment.namespace()).endMetadata()
                    .withNewSpec().addNewContainer().withName("budget-check-a")
                        .withImage("registry.k8s.io/pause:3.10").withResources(resources()).endContainer()
                    .addNewContainer().withName("budget-check-b")
                        .withImage("registry.k8s.io/pause:3.10").withResources(resources()).endContainer()
                    .addNewContainer().withName("budget-check-c")
                        .withImage("registry.k8s.io/pause:3.10").withResources(resources()).endContainer()
                    .endSpec().build();
            var quota = client.resourceQuotas().inNamespace(environment.namespace())
                    .withName(KubernetesEnvironmentProvisioner.RESOURCE_QUOTA).get();
            String defaultsName = "limit-range-defaults-" + environment.id().toString().substring(0, 8);
            Pod defaultPod = new PodBuilder()
                    .withNewMetadata().withName(defaultsName).withNamespace(environment.namespace()).endMetadata()
                    .withNewSpec().addNewContainer().withName("limit-range-defaults")
                        .withImage("registry.k8s.io/pause:3.10").endContainer()
                    .endSpec().build();
            try {
                Pod admitted = client.pods().inNamespace(environment.namespace()).resource(defaultPod).create();
                var defaults = admitted.getSpec().getContainers().get(0).getResources();
                assertEquals("100m", defaults.getRequests().get("cpu").toString());
                assertEquals("64Mi", defaults.getRequests().get("memory").toString());
                assertEquals("500m", defaults.getLimits().get("cpu").toString());
                assertEquals("256Mi", defaults.getLimits().get("memory").toString());
            } finally {
                client.pods().inNamespace(environment.namespace()).withName(defaultsName).delete();
            }
            try {
                client.pods().inNamespace(environment.namespace()).resource(pod).create();
                client.pods().inNamespace(environment.namespace()).withName(podName).delete();
                throw new AssertionError("ResourceQuota accepted a Pod exceeding requests.cpu and requests.memory");
            } catch (KubernetesClientException exception) {
                assertEquals(403, exception.getCode(),
                            "Expected quota admission rejection; ResourceQuota hard=" + quota.getSpec().getHard()
                                    + ", used=" + (quota.getStatus() == null ? "pending" : quota.getStatus().getUsed()));
            }
        }
    }

    private static String readinessDiagnostics(KubernetesClient client, String namespace) {
        return client.pods().inNamespace(namespace).list().getItems().stream()
                .map(pod -> {
                    var status = pod.getStatus();
                    String containers = status == null || status.getContainerStatuses() == null
                            ? "unknown"
                            : status.getContainerStatuses().stream().map(KindProvisioningTests::containerDiagnostic)
                                    .collect(Collectors.joining(","));
                    return pod.getMetadata().getName() + " phase="
                            + (status == null ? "unknown" : status.getPhase()) + " containers=" + containers;
                }).collect(Collectors.joining("; "));
    }

    private static String containerDiagnostic(ContainerStatus status) {
        var state = status.getState();
        if (state != null && state.getWaiting() != null) {
            return status.getName() + " waiting=" + state.getWaiting().getReason();
        }
        if (state != null && state.getTerminated() != null) {
            return status.getName() + " terminated=" + state.getTerminated().getReason();
        }
        return status.getName() + " ready=" + status.getReady();
    }

    private static io.fabric8.kubernetes.api.model.ResourceRequirements resources() {
        return new io.fabric8.kubernetes.api.model.ResourceRequirementsBuilder()
                .withRequests(Map.of("cpu", new Quantity("1"), "memory", new Quantity("1Gi")))
                .withLimits(Map.of("cpu", new Quantity("1"), "memory", new Quantity("1Gi")))
                .build();
    }
}

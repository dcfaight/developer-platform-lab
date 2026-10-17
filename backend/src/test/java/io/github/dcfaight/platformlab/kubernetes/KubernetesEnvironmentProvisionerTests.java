package io.github.dcfaight.platformlab.kubernetes;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.DeleteOptions;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LimitRange;
import io.fabric8.kubernetes.api.model.ResourceQuota;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStatusBuilder;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.github.dcfaight.platformlab.environment.EnvironmentRepository.EnvironmentRecord;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KubernetesEnvironmentProvisionerTests {
    private HttpServer server;
    private KubernetesEnvironmentProvisioner provisioner;
    private final EnvironmentRecord environment = environment();
    private Namespace namespace;
    private ResourceQuota resourceQuota;
    private LimitRange limitRange;
    private Deployment deployment;
    private int writes;
    private int failure;
    private int createFailure;
    private String createFailureKind;
    private boolean createRace;
    private boolean staleUpdate;
    private boolean redirect;
    private int redirectedRequests;
    private final UUID createOperationId = UUID.randomUUID();
    private DeleteOptions deleteOptions;
    private int deletes;
    private boolean deleteRace;
    private boolean goneOnDelete;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        provisioner = newProvisioner();
    }

    private KubernetesEnvironmentProvisioner newProvisioner() {
        Config config = Config.empty();
        config.setMasterUrl("http://127.0.0.1:" + server.getAddress().getPort());
        config.setRequestRetryBackoffLimit(0);
        config.setRequestTimeout(2000);
        config.setHttpProxy(null);
        config.setHttpsProxy(null);
        return new KubernetesEnvironmentProvisioner(LocalKubernetesClient.build(config));
    }

    @AfterEach
    void stop() {
        provisioner.close();
        server.stop(0);
    }

    @Test
    void provisionsApprovedWorkloadAndRerunsWithoutWrites() {
        provisioner.provision(environment);
        assertEquals(4, writes);
        assertEquals("env-" + environment.id().toString().replace("-", ""), namespace.getMetadata().getName());
        assertEquals("platform-lab", namespace.getMetadata().getLabels()
                .get(KubernetesEnvironmentProvisioner.MANAGED_BY));
        assertEquals(environment.id().toString(), deployment.getMetadata().getAnnotations()
                .get(KubernetesEnvironmentProvisioner.ENVIRONMENT_ID));
        var pod = deployment.getSpec().getTemplate().getSpec();
        var container = pod.getContainers().get(0);
        assertEquals(KubernetesEnvironmentProvisioner.IMAGE, container.getImage());
        assertEquals(8080, container.getPorts().get(0).getContainerPort());
        assertNotNull(container.getReadinessProbe().getHttpGet());
        assertNotNull(container.getLivenessProbe().getHttpGet());
        assertEquals("50m", container.getResources().getRequests().get("cpu").toString());
        assertEquals("32Mi", container.getResources().getRequests().get("memory").toString());
        assertEquals("250m", container.getResources().getLimits().get("cpu").toString());
        assertEquals("128Mi", container.getResources().getLimits().get("memory").toString());
        assertEquals("2", resourceQuota.getSpec().getHard().get("requests.cpu").toString());
        assertEquals("2Gi", resourceQuota.getSpec().getHard().get("requests.memory").toString());
        assertEquals("4", resourceQuota.getSpec().getHard().get("limits.cpu").toString());
        assertEquals("4Gi", resourceQuota.getSpec().getHard().get("limits.memory").toString());
        assertEquals("10", resourceQuota.getSpec().getHard().get("pods").toString());
        assertEquals("100m", limitRange.getSpec().getLimits().get(0).getDefaultRequest().get("cpu").toString());
        assertEquals("64Mi", limitRange.getSpec().getLimits().get(0).getDefaultRequest().get("memory").toString());
        assertEquals("500m", limitRange.getSpec().getLimits().get(0).getDefault().get("cpu").toString());
        assertEquals("256Mi", limitRange.getSpec().getLimits().get(0).getDefault().get("memory").toString());
        assertEquals("1", limitRange.getSpec().getLimits().get(0).getMax().get("cpu").toString());
        assertEquals("1Gi", limitRange.getSpec().getLimits().get(0).getMax().get("memory").toString());
        assertTrue(container.getResources().getRequests().get("cpu")
                .compareTo(resourceQuota.getSpec().getHard().get("requests.cpu")) < 0);
        assertTrue(container.getResources().getRequests().get("memory")
                .compareTo(resourceQuota.getSpec().getHard().get("requests.memory")) < 0);
        assertTrue(container.getResources().getLimits().get("cpu")
                .compareTo(resourceQuota.getSpec().getHard().get("limits.cpu")) < 0);
        assertTrue(container.getResources().getLimits().get("memory")
                .compareTo(resourceQuota.getSpec().getHard().get("limits.memory")) < 0);
        assertTrue(container.getResources().getLimits().get("cpu")
                .compareTo(limitRange.getSpec().getLimits().get(0).getMax().get("cpu")) < 0);
        assertTrue(container.getResources().getLimits().get("memory")
                .compareTo(limitRange.getSpec().getLimits().get(0).getMax().get("memory")) < 0);
        assertTrue(container.getSecurityContext().getReadOnlyRootFilesystem());
        assertFalse(container.getSecurityContext().getAllowPrivilegeEscalation());
        assertFalse(pod.getAutomountServiceAccountToken());
        assertTrue(pod.getSecurityContext().getRunAsNonRoot());
        provisioner.provision(environment);
        assertEquals(4, writes);
    }

    @Test
    void restartAfterAcceptedProvisioningObservesResourcesWithoutAnotherWrite() {
        provisioner.provision(environment, createOperationId);
        String uid = namespace.getMetadata().getUid();
        provisioner.close();
        provisioner = newProvisioner();

        provisioner.provision(environment, createOperationId, () -> {
            fail("Recovery of matching resources must not request another mutation");
            return false;
        });
        assertEquals(uid, namespace.getMetadata().getUid());
        assertEquals(4, writes);
    }

    @Test
    void restartAfterNamespaceCreationReconcilesRemainingResources() {
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        provisioner.provision(environment, createOperationId, () -> checks.incrementAndGet() == 1);
        assertNotNull(namespace);
        assertNull(deployment);
        assertNull(resourceQuota);
        assertEquals(1, writes);
        String uid = namespace.getMetadata().getUid();
        provisioner.close();
        provisioner = newProvisioner();

        provisioner.provision(environment, createOperationId, () -> true);
        assertNotNull(deployment);
        assertNotNull(resourceQuota);
        assertNotNull(limitRange);
        assertEquals(uid, namespace.getMetadata().getUid());
        assertEquals(4, writes);
        provisioner.provision(environment, createOperationId, () -> true);
        assertEquals(4, writes);
    }

    @Test
    void readinessRequiresObservedCurrentGenerationAndActualReplicas() {
        assertFalse(provisioner.isReady(environment));
        provisioner.provision(environment);
        assertFalse(provisioner.isReady(environment));
        deployment.getMetadata().setGeneration(2L);
        deployment.setStatus(new DeploymentStatusBuilder().withObservedGeneration(1L)
                .withReplicas(1).withUpdatedReplicas(1).withAvailableReplicas(1).build());
        assertFalse(provisioner.isReady(environment));
        deployment.getStatus().setObservedGeneration(2L);
        deployment.getStatus().setAvailableReplicas(0);
        assertFalse(provisioner.isReady(environment));
        deployment.getStatus().setAvailableReplicas(1);
        assertTrue(provisioner.isReady(environment));
        deployment.getSpec().getTemplate().getSpec().getContainers().get(0).setImage("unapproved");
        assertFalse(provisioner.isReady(environment));
    }

    @Test
    void refusesUnownedNamespaceWithoutMutation() {
        namespace = new NamespaceBuilder().withNewMetadata().withName(environment.namespace()).endMetadata().build();
        assertPermanent("KUBERNETES_OWNERSHIP_CONFLICT", () -> provisioner.provision(environment));
        assertPermanent("KUBERNETES_OWNERSHIP_CONFLICT", () -> provisioner.isReady(environment));
        assertPermanent("KUBERNETES_OWNERSHIP_CONFLICT",
                () -> provisioner.delete(environment, createOperationId, () -> true));
        assertEquals(0, writes);
    }

    @Test
    void refusesUnownedDeploymentAndTerminatingNamespace() {
        provisioner.provision(environment);
        deployment.getMetadata().setAnnotations(Map.of());
        assertPermanent("KUBERNETES_OWNERSHIP_CONFLICT", () -> provisioner.provision(environment));
        assertPermanent("KUBERNETES_OWNERSHIP_CONFLICT", () -> provisioner.isReady(environment));
        namespace.getMetadata().setDeletionTimestamp("2026-01-01T00:00:00Z");
        assertPermanent("KUBERNETES_OWNERSHIP_CONFLICT", () -> provisioner.provision(environment));
        assertEquals(4, writes);
    }

    @Test
    void refusesUnownedQuotaOrLimitRangeWithoutMutation() {
        provisioner.provision(environment);
        resourceQuota.getMetadata().getAnnotations().clear();
        assertPermanent("KUBERNETES_OWNERSHIP_CONFLICT", () -> provisioner.provision(environment));
        resourceQuota.getMetadata().getAnnotations().putAll(Map.of(
                KubernetesEnvironmentProvisioner.MANAGED_BY, "platform-lab",
                KubernetesEnvironmentProvisioner.ENVIRONMENT_ID, environment.id().toString()));
        limitRange.getMetadata().getLabels().clear();
        assertPermanent("KUBERNETES_OWNERSHIP_CONFLICT", () -> provisioner.provision(environment));
        assertEquals(4, writes);
    }

    @Test
    void reconcilesOwnedQuotaDriftWithResourceVersion() {
        provisioner.provision(environment);
        resourceQuota.getSpec().getHard().put("pods", new io.fabric8.kubernetes.api.model.Quantity("20"));
        provisioner.provision(environment);
        assertEquals("10", resourceQuota.getSpec().getHard().get("pods").toString());
        assertEquals(5, writes);
    }

    @Test
    void doesNotAdoptResourceAppearingDuringCreateRace() {
        createRace = true;
        var error = assertThrows(ProvisioningException.class, () -> provisioner.provision(environment));
        assertTrue(error.retryable());
        assertNull(deployment);
        assertEquals(0, writes);
    }

    @Test
    void reconcilesDriftWithResourceVersionAndDoesNotForceConflicts() {
        provisioner.provision(environment);
        deployment.getSpec().getTemplate().getSpec().getContainers().get(0).setImage("unapproved");
        provisioner.provision(environment);
        assertEquals(5, writes);
        assertEquals(KubernetesEnvironmentProvisioner.IMAGE,
                deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
        deployment.getSpec().setReplicas(2);
        staleUpdate = true;
        assertTrue(assertThrows(ProvisioningException.class, () -> provisioner.provision(environment)).retryable());
        assertEquals(2, deployment.getSpec().getReplicas());
        assertEquals(5, writes);
    }

    @Test
    void mapsErrorsWithoutLeakingUpstreamMessage() {
        for (int code : new int[] {409, 429, 500, 503, 401, 403, 422}) {
            failure = code;
            for (Runnable operation : new Runnable[] {
                    () -> provisioner.provision(environment),
                    () -> provisioner.isReady(environment),
                    () -> provisioner.delete(environment, createOperationId, () -> true)}) {
                var error = assertThrows(ProvisioningException.class, operation::run);
                boolean retryable = code == 409 || code == 429 || code >= 500;
                assertEquals(retryable, error.retryable());
                assertEquals(switch (code) {
                    case 401 -> "KUBERNETES_AUTHENTICATION_FAILED";
                    case 403 -> "KUBERNETES_FORBIDDEN";
                    default -> retryable ? "KUBERNETES_UNAVAILABLE" : "KUBERNETES_REQUEST_REJECTED";
                }, error.code());
                assertEquals(switch (code) {
                    case 401 -> "The Kubernetes API did not accept the worker credentials.";
                    case 403 -> "The worker does not have permission to access the required Kubernetes resources.";
                    default -> retryable ? "The Kubernetes API is temporarily unavailable."
                            : "The Kubernetes API rejected the environment request.";
                }, error.getMessage());
                assertFalse(error.getMessage().contains("private-upstream"));
                assertNull(error.getCause());
            }
        }
    }

    @Test
    void resourceCreationRejectionHasSafeFailureDetails() {
        createFailure = 422;
        var error = assertThrows(ProvisioningException.class, () -> provisioner.provision(environment));
        assertEquals("KUBERNETES_REQUEST_REJECTED", error.code());
        assertEquals("The Kubernetes API rejected the environment request.", error.getMessage());
        assertFalse(error.retryable());
        assertNull(error.getCause());
        assertNull(namespace);
        assertEquals(0, writes);
    }

    @Test
    void quotaCreationFailureUsesSanitizedActionableDetails() {
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        provisioner.provision(environment, createOperationId, () -> checks.incrementAndGet() == 1);
        createFailure = 422;
        createFailureKind = "ResourceQuota";
        var error = assertThrows(ProvisioningException.class,
                () -> provisioner.provision(environment, createOperationId));
        assertEquals("KUBERNETES_REQUEST_REJECTED", error.code());
        assertEquals("The Kubernetes API rejected the environment request.", error.getMessage());
        assertNull(error.getCause());
        assertNotNull(namespace);
        assertNull(resourceQuota);
        assertNull(deployment);
        assertEquals(1, writes);
    }

    @Test
    void mapsConnectionFailureToRetryableError() {
        server.stop(0);
        var error = assertThrows(ProvisioningException.class, () -> provisioner.provision(environment));
        assertTrue(error.retryable());
        assertEquals("The Kubernetes API is temporarily unavailable.", error.getMessage());
    }

    @Test
    void neverFollowsApiRedirects() {
        redirect = true;
        assertThrows(ProvisioningException.class, () -> provisioner.provision(environment));
        assertEquals(0, redirectedRequests);
    }

    @Test
    void validatesPersistedNamespaceBeforeAnyRequests() {
        var invalid = new EnvironmentRecord(environment.id(), "bad", "default", "nginx", "PROVISIONING",
                Instant.now(), Instant.now(), Instant.now());
        assertPermanent("KUBERNETES_VALIDATION_FAILED", () -> provisioner.provision(invalid));
        assertPermanent("KUBERNETES_VALIDATION_FAILED", () -> provisioner.isReady(invalid));
        assertEquals(0, writes);
    }

    @Test
    void deletionUsesBothPreconditionsAndWaitsForActualAbsence() {
        provisioner.provision(environment, createOperationId);
        String uid = namespace.getMetadata().getUid();
        String resourceVersion = namespace.getMetadata().getResourceVersion();
        assertFalse(provisioner.delete(environment, createOperationId, () -> true));
        assertEquals(uid, deleteOptions.getPreconditions().getUid());
        assertEquals(resourceVersion, deleteOptions.getPreconditions().getResourceVersion());
        assertEquals(1, deletes);
        assertFalse(provisioner.delete(environment, createOperationId, () -> true));
        assertEquals(1, deletes, "A terminating namespace must only be polled");
        assertFalse(provisioner.delete(environment, createOperationId, () -> {
            fail("A terminating namespace must not attempt another DELETE");
            return false;
        }));
        namespace = null;
        assertTrue(provisioner.delete(environment, createOperationId, () -> true));
        assertEquals(1, deletes);
    }

    @Test
    void milestonesAreReportedOnlyAfterKubernetesAcceptsEachWrite() {
        var milestones = new java.util.ArrayList<OperationProgress.Milestone>();
        var progress = new OperationProgress() {
            @Override
            public boolean getAsBoolean() {
                return true;
            }

            @Override
            public void milestone(Milestone milestone) {
                milestones.add(milestone);
            }
        };
        provisioner.provision(environment, createOperationId, progress);
        assertEquals(java.util.List.of(OperationProgress.Milestone.NAMESPACE_CREATED,
                OperationProgress.Milestone.WORKLOAD_CREATED), milestones);
        provisioner.provision(environment, createOperationId, progress);
        assertEquals(2, milestones.size(), "Observing existing resources is not a new milestone");

        assertFalse(provisioner.delete(environment, createOperationId, progress));
        assertEquals(OperationProgress.Milestone.NAMESPACE_DELETE_REQUESTED, milestones.get(2));
        assertFalse(provisioner.delete(environment, createOperationId, progress));
        namespace = null;
        assertTrue(provisioner.delete(environment, createOperationId, progress));
        assertEquals(3, milestones.size(), "Polling a terminating or absent namespace is not a new milestone");
    }

    @Test
    void restartAfterAcceptedDeletionOnlyObservesUntilNamespaceIsAbsent() {
        provisioner.provision(environment, createOperationId);
        assertFalse(provisioner.delete(environment, createOperationId, () -> true));
        provisioner.close();
        provisioner = newProvisioner();

        assertFalse(provisioner.delete(environment, createOperationId, () -> {
            fail("Recovery must not repeat an accepted DELETE");
            return false;
        }));
        namespace = null;
        assertTrue(provisioner.delete(environment, createOperationId, () -> false));
        assertEquals(1, deletes);
    }

    @Test
    void createChecksLeaseBeforeEachCreateMutation() {
        provisioner.provision(environment, createOperationId, () -> false);
        assertNull(namespace);
        assertEquals(0, writes);
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        provisioner.provision(environment, createOperationId, () -> checks.incrementAndGet() < 1);
        assertNull(namespace);
        assertEquals(0, writes);
        checks.set(0);
        provisioner.provision(environment, createOperationId, () -> checks.incrementAndGet() < 2);
        assertNotNull(namespace);
        assertNull(deployment);
        assertEquals(1, writes);
    }

    @Test
    void checksLeaseBeforeEachGuardrailCreate() {
        var checks = new java.util.concurrent.atomic.AtomicInteger();
        provisioner.provision(environment, createOperationId, () -> checks.incrementAndGet() == 1);
        checks.set(0);
        provisioner.provision(environment, createOperationId, () -> checks.incrementAndGet() <= 1);
        assertNotNull(resourceQuota);
        assertNull(limitRange);
        assertEquals(2, writes);
    }

    @Test
    void createChecksLeaseBeforeReplacingDeployment() {
        provisioner.provision(environment, createOperationId);
        deployment.getSpec().setReplicas(2);
        provisioner.provision(environment, createOperationId, () -> false);
        assertEquals(4, writes);
        assertEquals(2, deployment.getSpec().getReplicas());
    }

    @Test
    void createNeverUsesNamespaceOwnedByDifferentCreateOperation() {
        provisioner.provision(environment, createOperationId);
        assertPermanent("KUBERNETES_OWNERSHIP_CONFLICT",
                () -> provisioner.provision(environment, UUID.randomUUID(), () -> true));
        assertEquals(4, writes);
    }

    @Test
    void deletionRequiresManagedLabelsAnnotationsAndOriginalCreateOperation() {
        provisioner.provision(environment, createOperationId);
        var owned = new NamespaceBuilder(namespace).build();
        for (String key : new String[] {KubernetesEnvironmentProvisioner.MANAGED_BY,
                KubernetesEnvironmentProvisioner.ENVIRONMENT_ID,
                KubernetesEnvironmentProvisioner.CREATE_OPERATION_ID}) {
            namespace = new NamespaceBuilder(owned).build();
            namespace.getMetadata().getLabels().put(key, UUID.randomUUID().toString());
            assertPermanent("KUBERNETES_OWNERSHIP_CONFLICT",
                    () -> provisioner.delete(environment, createOperationId, () -> true));
            namespace = new NamespaceBuilder(owned).build();
            namespace.getMetadata().getAnnotations().remove(key);
            assertPermanent("KUBERNETES_OWNERSHIP_CONFLICT",
                    () -> provisioner.delete(environment, createOperationId, () -> true));
        }
        namespace = owned;
        assertPermanent("KUBERNETES_OWNERSHIP_CONFLICT",
                () -> provisioner.delete(environment, UUID.randomUUID(), () -> true));
        assertPermanent("KUBERNETES_OWNERSHIP_CONFLICT",
                () -> provisioner.delete(environment, null, () -> true));
        assertEquals(0, deletes);
    }

    @Test
    void deletionSupportsOriginalPlatformOwnershipWithoutRelabeling() {
        provisioner.provision(environment);
        assertFalse(namespace.getMetadata().getLabels().containsKey(KubernetesEnvironmentProvisioner.CREATE_OPERATION_ID));
        assertFalse(namespace.getMetadata().getAnnotations()
                .containsKey(KubernetesEnvironmentProvisioner.CREATE_OPERATION_ID));
        assertPermanent("KUBERNETES_OWNERSHIP_CONFLICT",
                () -> provisioner.delete(environment, null, () -> true));
        String uid = namespace.getMetadata().getUid();
        String version = namespace.getMetadata().getResourceVersion();
        assertFalse(provisioner.delete(environment, createOperationId, () -> true));
        assertEquals(uid, deleteOptions.getPreconditions().getUid());
        assertEquals(version, deleteOptions.getPreconditions().getResourceVersion());
        assertEquals(1, deletes);
        assertEquals(4, writes, "Legacy ownership must never be relabeled");
        assertFalse(namespace.getMetadata().getLabels().containsKey(KubernetesEnvironmentProvisioner.CREATE_OPERATION_ID));
        assertFalse(namespace.getMetadata().getAnnotations()
                .containsKey(KubernetesEnvironmentProvisioner.CREATE_OPERATION_ID));
        namespace = null;
        assertTrue(provisioner.delete(environment, createOperationId, () -> true));
    }

    @Test
    void deletionNeverTreatsPartialOrMismatchedCreateMarkersAsLegacy() {
        provisioner.provision(environment, createOperationId);
        var owned = new NamespaceBuilder(namespace).build();
        namespace.getMetadata().getLabels().remove(KubernetesEnvironmentProvisioner.CREATE_OPERATION_ID);
        assertPermanent("KUBERNETES_OWNERSHIP_CONFLICT",
                () -> provisioner.delete(environment, createOperationId, () -> true));
        namespace = new NamespaceBuilder(owned).build();
        namespace.getMetadata().getAnnotations().remove(KubernetesEnvironmentProvisioner.CREATE_OPERATION_ID);
        assertPermanent("KUBERNETES_OWNERSHIP_CONFLICT",
                () -> provisioner.delete(environment, createOperationId, () -> true));
        namespace = new NamespaceBuilder(owned).build();
        namespace.getMetadata().getAnnotations().put(KubernetesEnvironmentProvisioner.CREATE_OPERATION_ID,
                UUID.randomUUID().toString());
        assertPermanent("KUBERNETES_OWNERSHIP_CONFLICT",
                () -> provisioner.delete(environment, createOperationId, () -> true));
        assertEquals(0, deletes);
    }

    @Test
    void deletionRefusesNoncanonicalNamesMissingIdentityAndExpiredLease() {
        provisioner.provision(environment, createOperationId);
        var invalid = new EnvironmentRecord(environment.id(), "bad", "default", "sample-service", "DELETING",
                Instant.now(), Instant.now(), Instant.now());
        assertPermanent("KUBERNETES_VALIDATION_FAILED",
                () -> provisioner.delete(invalid, createOperationId, () -> true));
        namespace.getMetadata().setUid(null);
        assertPermanent("KUBERNETES_VALIDATION_FAILED",
                () -> provisioner.delete(environment, createOperationId, () -> true));
        namespace.getMetadata().setUid(UUID.randomUUID().toString());
        namespace.getMetadata().setResourceVersion(null);
        assertPermanent("KUBERNETES_VALIDATION_FAILED",
                () -> provisioner.delete(environment, createOperationId, () -> true));
        namespace.getMetadata().setResourceVersion("1");
        assertFalse(provisioner.delete(environment, createOperationId, () -> false));
        assertEquals(0, deletes);
    }

    @Test
    void deletionDoesNotRemoveReplacementBetweenGetAndDelete() {
        provisioner.provision(environment, createOperationId);
        String originalUid = namespace.getMetadata().getUid();
        deleteRace = true;
        var error = assertThrows(ProvisioningException.class,
                () -> provisioner.delete(environment, createOperationId, () -> true));
        assertTrue(error.retryable());
        assertEquals(originalUid, deleteOptions.getPreconditions().getUid());
        assertNotEquals(originalUid, namespace.getMetadata().getUid());
        assertEquals(0, deletes);
        assertPermanent("KUBERNETES_OWNERSHIP_CONFLICT",
                () -> provisioner.delete(environment, createOperationId, () -> true));
    }

    @Test
    void disappearanceBetweenGetAndDeleteSucceeds() {
        provisioner.provision(environment, createOperationId);
        goneOnDelete = true;
        assertTrue(provisioner.delete(environment, createOperationId, () -> true));
        assertNull(namespace);
    }

    private static void assertPermanent(String code, org.junit.jupiter.api.function.Executable action) {
        var error = assertThrows(ProvisioningException.class, action);
        assertEquals(code, error.code());
        assertFalse(error.retryable());
    }

    static EnvironmentRecord environment() {
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        return new EnvironmentRecord(id, "test", "env-" + id.toString().replace("-", ""), "sample-service", "PROVISIONING",
                now.plusSeconds(3600), now, now);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (exchange.getRequestURI().getPath().equals("/redirect-target")) {
                redirectedRequests++;
                respond(exchange, 403, null);
                return;
            }
            if (redirect) {
                exchange.getResponseHeaders().set("Location",
                        "http://127.0.0.1:" + server.getAddress().getPort() + "/redirect-target");
                respond(exchange, 302, null);
                return;
            }
            if (failure != 0) {
                respond(exchange, failure, null);
                return;
            }
            String path = exchange.getRequestURI().getPath();
            String namespacedPrefix = "/api/v1/namespaces/" + environment.namespace() + "/";
            boolean namespaces = path.equals("/api/v1/namespaces")
                    || path.equals("/api/v1/namespaces/" + environment.namespace());
            boolean quotas = path.startsWith(namespacedPrefix + "resourcequotas");
            boolean limitRanges = path.startsWith(namespacedPrefix + "limitranges");
            boolean deployments = path.startsWith("/apis/apps/v1/namespaces/" + environment.namespace()
                    + "/deployments");
            if (!namespaces && !quotas && !limitRanges && !deployments) {
                respond(exchange, 404, null);
                return;
            }
            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) {
                Object resource = namespaces ? namespace : quotas ? resourceQuota
                        : limitRanges ? limitRange : deployment;
                respond(exchange, resource == null ? 404 : 200, resource);
            } else if ("POST".equals(method)) {
                String kind = namespaces ? "Namespace" : quotas ? "ResourceQuota"
                        : limitRanges ? "LimitRange" : "Deployment";
                if (createFailure != 0 && (createFailureKind == null || createFailureKind.equals(kind))) {
                    respond(exchange, createFailure, null);
                    return;
                }
                if (createRace) {
                    respond(exchange, 409, null);
                    return;
                }
                String json = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (namespaces) {
                    namespace = Serialization.unmarshal(json, Namespace.class);
                    namespace.getMetadata().setResourceVersion("1");
                    namespace.getMetadata().setUid(UUID.randomUUID().toString());
                } else if (quotas) {
                    resourceQuota = Serialization.unmarshal(json, ResourceQuota.class);
                    resourceQuota.getMetadata().setResourceVersion("1");
                } else if (limitRanges) {
                    limitRange = Serialization.unmarshal(json, LimitRange.class);
                    limitRange.getMetadata().setResourceVersion("1");
                } else {
                    if (resourceQuota == null || limitRange == null) {
                        respond(exchange, 409, null);
                        return;
                    }
                    deployment = Serialization.unmarshal(json, Deployment.class);
                    deployment.getMetadata().setResourceVersion("1");
                    deployment.getMetadata().setGeneration(1L);
                }
                writes++;
                respond(exchange, 201, namespaces ? namespace : quotas ? resourceQuota
                        : limitRanges ? limitRange : deployment);
            } else if ("DELETE".equals(method) && namespaces) {
                deleteOptions = Serialization.unmarshal(exchange.getRequestBody(), DeleteOptions.class);
                if (goneOnDelete) {
                    namespace = null;
                    respond(exchange, 404, null);
                    return;
                }
                if (deleteRace) {
                    namespace.getMetadata().setUid(UUID.randomUUID().toString());
                    namespace.getMetadata().getLabels().put(KubernetesEnvironmentProvisioner.CREATE_OPERATION_ID,
                            UUID.randomUUID().toString());
                }
                if (!namespace.getMetadata().getUid().equals(deleteOptions.getPreconditions().getUid())
                        || !namespace.getMetadata().getResourceVersion()
                            .equals(deleteOptions.getPreconditions().getResourceVersion())) {
                    respond(exchange, 409, null);
                    return;
                }
                deletes++;
                namespace.getMetadata().setDeletionTimestamp("2026-01-01T00:00:00Z");
                respond(exchange, 202, namespace);
            } else if ("PUT".equals(method) && (deployments || quotas || limitRanges)) {
                HasMetadata existing = deployments ? deployment : quotas ? resourceQuota : limitRange;
                HasMetadata update = deployments ? Serialization.unmarshal(
                        exchange.getRequestBody(), Deployment.class) : quotas ? Serialization.unmarshal(
                        exchange.getRequestBody(), ResourceQuota.class) : Serialization.unmarshal(
                        exchange.getRequestBody(), LimitRange.class);
                if ((staleUpdate && deployments) || !existing.getMetadata().getResourceVersion()
                        .equals(update.getMetadata().getResourceVersion())) {
                    respond(exchange, 409, null);
                    return;
                }
                update.getMetadata().setResourceVersion("2");
                if (deployments) {
                    deployment = (Deployment) update;
                } else if (quotas) {
                    resourceQuota = (ResourceQuota) update;
                } else {
                    limitRange = (LimitRange) update;
                }
                writes++;
                respond(exchange, 200, update);
            } else {
                respond(exchange, 405, null);
            }
        } finally {
            exchange.close();
        }
    }

    private static void respond(HttpExchange exchange, int code, Object resource) throws IOException {
        byte[] bytes = (resource != null ? Serialization.asJson(resource)
                : "{\"apiVersion\":\"v1\",\"kind\":\"Status\",\"status\":\"Failure\","
                    + "\"message\":\"private-upstream\",\"code\":" + code + "}")
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}

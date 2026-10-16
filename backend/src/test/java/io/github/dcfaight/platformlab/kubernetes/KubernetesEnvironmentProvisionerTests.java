package io.github.dcfaight.platformlab.kubernetes;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
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
    private Deployment deployment;
    private int writes;
    private int failure;
    private boolean createRace;
    private boolean staleUpdate;
    private boolean redirect;
    private int redirectedRequests;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
        Config config = Config.empty();
        config.setMasterUrl("http://127.0.0.1:" + server.getAddress().getPort());
        config.setRequestRetryBackoffLimit(0);
        config.setRequestTimeout(2000);
        config.setHttpProxy(null);
        config.setHttpsProxy(null);
        provisioner = new KubernetesEnvironmentProvisioner(LocalKubernetesClient.build(config));
    }

    @AfterEach
    void stop() {
        provisioner.close();
        server.stop(0);
    }

    @Test
    void provisionsApprovedWorkloadAndRerunsWithoutWrites() {
        provisioner.provision(environment);
        assertEquals(2, writes);
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
        assertEquals("128Mi", container.getResources().getLimits().get("memory").toString());
        assertTrue(container.getSecurityContext().getReadOnlyRootFilesystem());
        assertFalse(container.getSecurityContext().getAllowPrivilegeEscalation());
        assertFalse(pod.getAutomountServiceAccountToken());
        assertTrue(pod.getSecurityContext().getRunAsNonRoot());
        provisioner.provision(environment);
        assertEquals(2, writes);
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
        assertEquals(2, writes);
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
        assertEquals(3, writes);
        assertEquals(KubernetesEnvironmentProvisioner.IMAGE,
                deployment.getSpec().getTemplate().getSpec().getContainers().get(0).getImage());
        deployment.getSpec().setReplicas(2);
        staleUpdate = true;
        assertTrue(assertThrows(ProvisioningException.class, () -> provisioner.provision(environment)).retryable());
        assertEquals(2, deployment.getSpec().getReplicas());
        assertEquals(3, writes);
    }

    @Test
    void mapsErrorsWithoutLeakingUpstreamMessage() {
        for (int code : new int[] {409, 429, 500, 503, 401, 403, 422}) {
            failure = code;
            var error = assertThrows(ProvisioningException.class, () -> provisioner.provision(environment));
            assertEquals(code == 409 || code == 429 || code >= 500, error.retryable());
            assertFalse(error.getMessage().contains("private-upstream"));
            assertNull(error.getCause());
        }
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
            boolean namespaces = path.startsWith("/api/v1/namespaces");
            boolean deployments = path.startsWith("/apis/apps/v1/namespaces/" + environment.namespace()
                    + "/deployments");
            if (!namespaces && !deployments) {
                respond(exchange, 404, null);
                return;
            }
            String method = exchange.getRequestMethod();
            if ("GET".equals(method)) {
                Object resource = namespaces ? namespace : deployment;
                respond(exchange, resource == null ? 404 : 200, resource);
            } else if ("POST".equals(method)) {
                if (createRace) {
                    respond(exchange, 409, null);
                    return;
                }
                String json = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (namespaces) {
                    namespace = Serialization.unmarshal(json, Namespace.class);
                    namespace.getMetadata().setResourceVersion("1");
                    namespace.getMetadata().setUid(UUID.randomUUID().toString());
                } else {
                    deployment = Serialization.unmarshal(json, Deployment.class);
                    deployment.getMetadata().setResourceVersion("1");
                    deployment.getMetadata().setGeneration(1L);
                }
                writes++;
                respond(exchange, 201, namespaces ? namespace : deployment);
            } else if ("PUT".equals(method) && deployments) {
                var update = Serialization.unmarshal(exchange.getRequestBody(), Deployment.class);
                if (staleUpdate || !deployment.getMetadata().getResourceVersion()
                        .equals(update.getMetadata().getResourceVersion())) {
                    respond(exchange, 409, null);
                    return;
                }
                deployment = update;
                deployment.getMetadata().setResourceVersion("2");
                writes++;
                respond(exchange, 200, deployment);
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

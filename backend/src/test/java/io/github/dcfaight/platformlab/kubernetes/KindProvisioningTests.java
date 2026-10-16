package io.github.dcfaight.platformlab.kubernetes;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Duration;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

@EnabledIfEnvironmentVariable(named = "PLATFORM_KIND_TEST", matches = "true")
class KindProvisioningTests {
    @Test
    void provisionsRerunsAndObservesRealDeploymentReadiness() throws InterruptedException {
        String kubeconfig = System.getProperty("platform.kubernetes.kubeconfig",
                Path.of(System.getProperty("user.home"), ".kube", "config").toString());
        var environment = KubernetesEnvironmentProvisionerTests.environment();
        var client = LocalKubernetesClient.open(kubeconfig);
        try (var provisioner = new KubernetesEnvironmentProvisioner(client)) {
            provisioner.provision(environment);
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
            assertTrue(provisioner.isReady(environment));
            provisioner.provision(environment);
            assertTrue(provisioner.isReady(environment));
        }
        // Retain the namespace for inspection; this integration test never deletes cluster resources.
    }
}

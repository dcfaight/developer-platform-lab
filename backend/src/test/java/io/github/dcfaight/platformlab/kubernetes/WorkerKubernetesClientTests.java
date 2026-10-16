package io.github.dcfaight.platformlab.kubernetes;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class WorkerKubernetesClientTests {
    @Test
    void defaultsToLocalKindAndSupportsExplicitLocalKind() {
        try (var local = mockStatic(LocalKubernetesClient.class)) {
            KubernetesClient client = mock(KubernetesClient.class);
            local.when(() -> LocalKubernetesClient.open("explicit-kubeconfig")).thenReturn(client);
            var runner = new ApplicationContextRunner()
                    .withUserConfiguration(KubernetesEnvironmentProvisioner.class)
                    .withPropertyValues("platform.worker.enabled=true",
                            "platform.kubernetes.kubeconfig=explicit-kubeconfig");
            runner.run(context -> {
                assertNull(context.getStartupFailure());
                assertNotNull(context.getBean(KubernetesEnvironmentProvisioner.class));
            });
            runner.withPropertyValues("platform.kubernetes.mode=local-kind").run(context -> {
                assertNull(context.getStartupFailure());
                assertNotNull(context.getBean(KubernetesEnvironmentProvisioner.class));
            });
            local.verify(() -> LocalKubernetesClient.open("explicit-kubeconfig"), times(2));
        }
    }

    @Test
    void selectsInClusterWithoutOpeningAnyKubeconfig() {
        try (var local = mockStatic(LocalKubernetesClient.class);
                var inCluster = mockStatic(InClusterKubernetesClient.class)) {
            Config projected = Config.empty();
            KubernetesClient client = mock(KubernetesClient.class);
            inCluster.when(InClusterKubernetesClient::configuration).thenReturn(projected);
            local.when(() -> LocalKubernetesClient.build(projected)).thenReturn(client);
            assertSame(client, WorkerKubernetesClient.open("in-cluster", null));
            new ApplicationContextRunner().withUserConfiguration(KubernetesEnvironmentProvisioner.class)
                    .withPropertyValues("platform.worker.enabled=true", "platform.kubernetes.mode=in-cluster",
                            "platform.kubernetes.kubeconfig=must-not-be-opened")
                    .run(context -> {
                        assertNull(context.getStartupFailure());
                        assertNotNull(context.getBean(KubernetesEnvironmentProvisioner.class));
                    });
            inCluster.verify(InClusterKubernetesClient::configuration, times(2));
            local.verify(() -> LocalKubernetesClient.build(projected), times(2));
            local.verifyNoMoreInteractions();
        }
    }

    @Test
    void rejectsUnknownEmptyAndNonExactModesWithoutOpeningAnyCredentials() {
        try (var local = mockStatic(LocalKubernetesClient.class);
                var inCluster = mockStatic(InClusterKubernetesClient.class)) {
            for (String mode : new String[] {null, "", "auto", "kind", "IN-CLUSTER", "in-cluster ", " local-kind"}) {
                var error = assertThrows(ProvisioningException.class,
                        () -> WorkerKubernetesClient.open(mode, "untrusted-kubeconfig"));
                assertFalse(error.retryable());
                assertNull(error.getCause());
                assertFalse(error.getMessage().contains("untrusted-kubeconfig"));
            }
            local.verifyNoInteractions();
            inCluster.verifyNoInteractions();
        }
    }
}

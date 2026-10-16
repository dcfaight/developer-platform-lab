package io.github.dcfaight.platformlab.kubernetes;

import io.fabric8.kubernetes.client.KubernetesClient;

final class WorkerKubernetesClient {
    private WorkerKubernetesClient() {
    }

    static KubernetesClient open(String mode, String kubeconfig) {
        if ("local-kind".equals(mode)) {
            return LocalKubernetesClient.open(kubeconfig);
        }
        if ("in-cluster".equals(mode)) {
            try {
                return LocalKubernetesClient.build(InClusterKubernetesClient.configuration());
            } catch (ProvisioningException exception) {
                throw exception;
            } catch (Exception ignored) {
                throw new ProvisioningException("KUBERNETES_CLIENT_INITIALIZATION_FAILED",
                        "The in-cluster Kubernetes client could not be initialized.", false);
            }
        }
        throw new ProvisioningException("KUBERNETES_CONFIG_INVALID",
                "An explicit supported Kubernetes client mode is required.", false);
    }
}

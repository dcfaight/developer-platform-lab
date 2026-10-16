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
            } catch (Exception ignored) {
                throw InClusterKubernetesClient.invalid();
            }
        }
        throw new ProvisioningException("KUBERNETES_CONFIG_INVALID",
                "An explicit supported Kubernetes client mode is required.", false);
    }
}

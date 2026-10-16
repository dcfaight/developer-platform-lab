package io.github.dcfaight.platformlab.kubernetes;

import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;

public final class KindNamespaceAssertions {
    private KindNamespaceAssertions() {
    }

    public static void assertAbsent(String namespace) {
        String kubeconfig = System.getProperty("platform.kubernetes.kubeconfig",
                Path.of(System.getProperty("user.home"), ".kube", "config").toString());
        try (var client = LocalKubernetesClient.open(kubeconfig)) {
            assertNull(client.namespaces().withName(namespace).get(),
                    "The deleted environment namespace must actually be absent from kind.");
        }
    }
}

package io.github.dcfaight.platformlab.kubernetes;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalKubernetesClientTests {
    @Test
    void selectsOnlyExplicitKindContextAndResolvesRelativeCertificates() throws Exception {
        var config = configuration("https://127.0.0.1:6443", "", "");
        assertEquals("https://127.0.0.1:6443/", config.getMasterUrl());
        assertTrue(config.getCaCertFile().endsWith("target/ca.crt"));
        assertFalse(config.isTrustCerts());
        assertFalse(config.isDisableHostnameVerification());
        assertNull(config.getHttpProxy());
        assertNull(config.getHttpsProxy());
        assertNull(config.getAuthProvider());
    }

    @Test
    void rejectsRemoteEndpointsAndUnsafeUrlComponents() throws Exception {
        for (String endpoint : new String[] {"http://127.0.0.1:6443", "https://example.com",
                "https://10.0.0.1", "https://127.0.0.1.evil.example", "https://127.0.0.1@evil.example",
                "https://127.0.0.1:6443/remote", "https://127.0.0.1:6443?remote=true"}) {
            var error = assertThrows(ProvisioningException.class, () -> configuration(endpoint, "", ""));
            assertFalse(error.retryable());
            assertFalse(error.getMessage().contains(endpoint));
        }
    }

    @Test
    void rejectsCredentialExecutionImpersonationAndTlsBypasses() throws Exception {
        for (String user : new String[] {
                "      exec: {apiVersion: client.authentication.k8s.io/v1, command: arbitrary-command}\n",
                "      auth-provider: {name: arbitrary-provider}\n",
                "      as: administrator\n", "      as-groups: [administrators]\n",
                "      as-uid: administrator\n", "      tokenFile: arbitrary-file\n",
                "      username: administrator\n"}) {
            assertThrows(ProvisioningException.class, () -> configuration("https://127.0.0.1:6443", "", user));
        }
        for (String cluster : new String[] {"      insecure-skip-tls-verify: true\n",
                "      proxy-url: https://remote.example\n", "      tls-server-name: remote.example\n"}) {
            assertThrows(ProvisioningException.class,
                    () -> configuration("https://127.0.0.1:6443", cluster, ""));
        }
    }

    @Test
    void refusesMissingKindContextEvenIfCurrentContextIsValid() throws Exception {
        Path path = Path.of("target", "kubeconfig-" + UUID.randomUUID());
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, document("https://127.0.0.1:6443", "", "")
                    .replace("kind-developer-platform-lab", "another-cluster"));
            assertThrows(ProvisioningException.class, () -> LocalKubernetesClient.configuration(path));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static io.fabric8.kubernetes.client.Config configuration(String endpoint, String cluster,
            String user) throws Exception {
        Path path = Path.of("target", "kubeconfig-" + UUID.randomUUID());
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, document(endpoint, cluster, user));
            return LocalKubernetesClient.configuration(path);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static String document(String endpoint, String cluster, String user) {
        return """
                apiVersion: v1
                kind: Config
                current-context: remote-current-context-is-ignored
                contexts:
                  - name: kind-developer-platform-lab
                    context: {cluster: local, user: local}
                clusters:
                  - name: local
                    cluster:
                      server: %s
                      certificate-authority: ca.crt
                %susers:
                  - name: local
                    user:
                      client-certificate: client.crt
                      client-key: client.key
                %s""".formatted(endpoint, cluster, user);
    }
}

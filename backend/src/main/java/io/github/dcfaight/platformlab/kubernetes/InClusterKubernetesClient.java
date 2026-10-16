package io.github.dcfaight.platformlab.kubernetes;

import io.fabric8.kubernetes.client.Config;
import java.nio.file.Files;
import java.nio.file.Path;

final class InClusterKubernetesClient {
    static final Path CREDENTIALS = Path.of("/var/run/secrets/platform-worker");
    static final String SERVER = "https://kubernetes.default.svc:443";

    private InClusterKubernetesClient() {
    }

    static Config configuration() {
        return configuration(CREDENTIALS);
    }

    static Config configuration(Path credentials) {
        Path ca = credentials.resolve("ca.crt");
        Path token = credentials.resolve("token");
        readCredential(ca);
        readCredential(token);
        Config config = Config.empty();
        config.setAutoConfigure(false);
        config.setMasterUrl(SERVER);
        config.setCaCertFile(ca.toString());
        config.setTrustCerts(false);
        config.setDisableHostnameVerification(false);
        // Fabric8 calls the provider before each request. Reopen the projected path so
        // kubelet's atomic symlink replacement is observed without caching an old token.
        config.setOauthTokenProvider(() -> readCredential(token));
        config.setHttpProxy(null);
        config.setHttpsProxy(null);
        config.setNoProxy(new String[] {"*"});
        config.setConnectionTimeout(5000);
        config.setRequestTimeout(10000);
        config.setRequestRetryBackoffLimit(0);
        return config;
    }

    private static String readCredential(Path path) {
        try {
            if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw invalid();
            }
            String value = Files.readString(path).strip();
            if (value.isEmpty()) {
                throw invalid();
            }
            return value;
        } catch (Exception ignored) {
            throw invalid();
        }
    }

    static ProvisioningException invalid() {
        return new ProvisioningException("KUBERNETES_CONFIG_INVALID",
                "Readable projected in-cluster Kubernetes credentials are required.", false);
    }
}

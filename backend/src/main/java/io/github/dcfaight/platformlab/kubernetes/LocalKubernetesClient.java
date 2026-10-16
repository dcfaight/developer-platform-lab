package io.github.dcfaight.platformlab.kubernetes;

import io.fabric8.kubernetes.api.model.AuthInfo;
import io.fabric8.kubernetes.api.model.Cluster;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.fabric8.kubernetes.client.utils.Serialization;
import io.fabric8.kubernetes.client.http.HttpClient;
import io.fabric8.kubernetes.client.utils.HttpClientUtils;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class LocalKubernetesClient {
    static final String CONTEXT = "kind-developer-platform-lab";

    private LocalKubernetesClient() {
    }

    static KubernetesClient open(String kubeconfig) {
        try {
            return build(configuration(Path.of(kubeconfig)));
        } catch (Exception ignored) {
            throw invalid();
        }
    }

    static KubernetesClient build(Config config) {
        return new KubernetesClientBuilder().withConfig(config)
                .withHttpClientFactory(new HttpClient.Factory() {
                    @Override
                    public HttpClient.Builder newBuilder() {
                        HttpClient.Builder delegate = HttpClientUtils.getHttpClientFactory().newBuilder();
                        // Fabric8 enables redirects during common configuration. Suppress that opt-in on
                        // the public builder interface, independent of the selected transport implementation.
                        return (HttpClient.Builder) Proxy.newProxyInstance(
                                HttpClient.Builder.class.getClassLoader(), new Class<?>[] {HttpClient.Builder.class},
                                (proxy, method, arguments) -> {
                                    if ("followAllRedirects".equals(method.getName())) {
                                        return proxy;
                                    }
                                    try {
                                        Object result = method.invoke(delegate, arguments);
                                        return result == delegate ? proxy : result;
                                    } catch (InvocationTargetException exception) {
                                        throw exception.getCause();
                                    }
                                });
                    }
                }).build();
    }

    static Config configuration(Path path) {
        try {
            var document = Serialization.unmarshal(Files.readString(path),
                    io.fabric8.kubernetes.api.model.Config.class);
            var contexts = document.getContexts().stream().filter(c -> CONTEXT.equals(c.getName())).toList();
            if (contexts.size() != 1) {
                throw invalid();
            }
            var context = contexts.get(0).getContext();
            var clusters = document.getClusters().stream()
                    .filter(c -> c.getName().equals(context.getCluster())).toList();
            var users = document.getUsers().stream()
                    .filter(u -> u.getName().equals(context.getUser())).toList();
            if (clusters.size() != 1 || users.size() != 1) {
                throw invalid();
            }
            Cluster cluster = clusters.get(0).getCluster();
            AuthInfo user = users.get(0).getUser();
            validateServer(cluster.getServer());
            if (Boolean.TRUE.equals(cluster.getInsecureSkipTlsVerify()) || cluster.getProxyUrl() != null
                    || cluster.getTlsServerName() != null || !cluster.getAdditionalProperties().isEmpty()
                    || user.getExec() != null || user.getAuthProvider() != null || user.getAs() != null
                    || user.getAsUid() != null || nonempty(user.getAsGroups())
                    || (user.getAsUserExtra() != null && !user.getAsUserExtra().isEmpty())
                    || user.getTokenFile() != null || user.getUsername() != null || user.getPassword() != null
                    || !user.getAdditionalProperties().isEmpty()) {
                throw invalid();
            }
            // Build from empty, never auto-configure: ambient credentials, proxies and contexts are not trusted.
            Config config = Config.empty();
            config.setAutoConfigure(false);
            config.setMasterUrl(cluster.getServer());
            config.setTrustCerts(false);
            config.setDisableHostnameVerification(false);
            config.setCaCertData(cluster.getCertificateAuthorityData());
            config.setCaCertFile(relative(path, cluster.getCertificateAuthority()));
            config.setClientCertData(user.getClientCertificateData());
            config.setClientCertFile(relative(path, user.getClientCertificate()));
            config.setClientKeyData(user.getClientKeyData());
            config.setClientKeyFile(relative(path, user.getClientKey()));
            config.setOauthToken(user.getToken());
            config.setHttpProxy(null);
            config.setHttpsProxy(null);
            config.setNoProxy(new String[] {"*"});
            config.setConnectionTimeout(5000);
            config.setRequestTimeout(10000);
            config.setRequestRetryBackoffLimit(0);
            return config;
        } catch (Exception ignored) {
            throw invalid();
        }
    }

    private static boolean nonempty(List<?> values) {
        return values != null && !values.isEmpty();
    }

    private static String relative(Path kubeconfig, String value) {
        return value == null ? null : kubeconfig.toAbsolutePath().getParent().resolve(value).normalize().toString();
    }

    private static void validateServer(String server) {
        URI uri = URI.create(server);
        String host = uri.getHost();
        boolean loopback = "localhost".equalsIgnoreCase(host) || "[::1]".equals(host) || "::1".equals(host);
        if (host != null && host.matches("127\\.[0-9]{1,3}\\.[0-9]{1,3}\\.[0-9]{1,3}")) {
            loopback = java.util.Arrays.stream(host.split("\\.")).allMatch(part -> Integer.parseInt(part) <= 255);
        }
        if (!"https".equals(uri.getScheme()) || !loopback || uri.getRawUserInfo() != null
                || uri.getRawQuery() != null || uri.getRawFragment() != null
                || (uri.getPath() != null && !uri.getPath().isEmpty() && !"/".equals(uri.getPath()))) {
            throw invalid();
        }
    }

    private static ProvisioningException invalid() {
        return new ProvisioningException("KUBERNETES_CONFIG_INVALID",
                "A trusted local kind Kubernetes configuration is required.", false);
    }
}

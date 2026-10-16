package io.github.dcfaight.platformlab.kubernetes;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.client.Config;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class InClusterKubernetesClientTests {
    private Path credentials;

    @BeforeEach
    void credentials() throws Exception {
        credentials = Files.createDirectories(Path.of("target", "projected-credentials-" + UUID.randomUUID()));
        var trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init((KeyStore) null);
        var certificate = ((X509TrustManager) trust.getTrustManagers()[0]).getAcceptedIssuers()[0];
        Files.writeString(credentials.resolve("ca.crt"), "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(certificate.getEncoded())
                + "\n-----END CERTIFICATE-----\n");
        Files.writeString(credentials.resolve("token"), "first-projected-token\n");
    }

    @AfterEach
    void cleanup() throws Exception {
        try (var files = Files.walk(credentials)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }

    @Test
    void fixesApiEndpointCredentialPathsAndTlsWithoutAmbientConfiguration() {
        Map<String, String> ambient = Map.ofEntries(
                Map.entry("kubeconfig", credentials.resolve("not-a-kubeconfig").toString()),
                Map.entry("kubernetes.master", "http://untrusted.invalid"),
                Map.entry("kubernetes.auth.token", "ambient-token"),
                Map.entry("kubernetes.auth.serviceAccount.token", "ambient-token-file"),
                Map.entry("kubernetes.auth.basic.username", "ambient-user"),
                Map.entry("kubernetes.auth.basic.password", "ambient-password"),
                Map.entry("kubernetes.certs.client.file", "ambient-client-cert"),
                Map.entry("kubernetes.certs.client.key.file", "ambient-client-key"),
                Map.entry("kubernetes.certs.ca.file", "ambient-ca"),
                Map.entry("kubernetes.trust.certificates", "true"),
                Map.entry("kubernetes.disable.hostname.verification", "true"),
                Map.entry("http.proxy", "http://untrusted.invalid:8888"),
                Map.entry("https.proxy", "http://untrusted.invalid:8888"));
        Map<String, String> previous = new LinkedHashMap<>();
        try {
            ambient.forEach((key, value) -> previous.put(key, System.setProperty(key, value)));
            Config config = InClusterKubernetesClient.configuration(credentials);
            assertEquals("https://kubernetes.default.svc:443/", config.getMasterUrl());
            assertEquals(Path.of("/var/run/secrets/platform-worker"), InClusterKubernetesClient.CREDENTIALS);
            assertEquals(credentials.resolve("ca.crt").toString(), config.getCaCertFile());
            assertFalse(config.getAutoConfigure());
            assertFalse(config.isTrustCerts());
            assertFalse(config.isDisableHostnameVerification());
            assertNull(config.getCaCertData());
            assertNull(config.getOauthToken());
            assertNull(config.getAutoOAuthToken());
            assertEquals("first-projected-token", config.getOauthTokenProvider().getToken());
            assertNull(config.getUsername());
            assertNull(config.getPassword());
            assertNull(config.getClientCertFile());
            assertNull(config.getClientCertData());
            assertNull(config.getClientKeyFile());
            assertNull(config.getClientKeyData());
            assertNull(config.getAuthProvider());
            assertNull(config.getImpersonateUsername());
            assertNull(config.getHttpProxy());
            assertNull(config.getHttpsProxy());
            assertNull(config.getProxyUsername());
            assertNull(config.getProxyPassword());
            assertArrayEquals(new String[] {"*"}, config.getNoProxy());
            try (var client = LocalKubernetesClient.build(config)) {
                Config built = client.getConfiguration();
                assertEquals(config.getMasterUrl(), built.getMasterUrl());
                assertEquals(config.getCaCertFile(), built.getCaCertFile());
                assertFalse(built.isTrustCerts());
                assertFalse(built.isDisableHostnameVerification());
                assertNull(built.getOauthToken());
                assertNull(built.getClientCertFile());
                assertNull(built.getHttpProxy());
                assertNull(built.getHttpsProxy());
                assertEquals("first-projected-token", built.getOauthTokenProvider().getToken());
            }
        } finally {
            previous.forEach((key, value) -> {
                if (value == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, value);
                }
            });
        }
    }

    @Test
    void refusesMissingEmptyBlankOrNonFileCredentialsAtStartup() throws Exception {
        for (String name : List.of("ca.crt", "token")) {
            Path file = credentials.resolve(name);
            for (String invalid : List.of("", " \n\t")) {
                Files.writeString(file, invalid);
                assertInvalid(() -> InClusterKubernetesClient.configuration(credentials));
            }
            Files.delete(file);
            assertInvalid(() -> InClusterKubernetesClient.configuration(credentials));
            Files.createDirectory(file);
            assertInvalid(() -> InClusterKubernetesClient.configuration(credentials));
            Files.delete(file);
            Files.writeString(file, "restored-projected-credential");
        }
    }

    @Test
    void reopensProjectedTokenSymlinkForEachActualFabric8RequestAndFailsClosedAfterRemoval() throws Exception {
        Path original = Files.writeString(credentials.resolve("token-v1"), "first-projected-token\n");
        Path token = credentials.resolve("token");
        Files.delete(token);
        Files.createSymbolicLink(token, original.getFileName());
        Config config = InClusterKubernetesClient.configuration(credentials);
        List<String> authorization = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/namespaces", exchange -> {
            authorization.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = "{\"apiVersion\":\"v1\",\"kind\":\"NamespaceList\",\"items\":[]}".getBytes();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        // Only this transport test uses loopback HTTP; production never overrides the fixed TLS endpoint.
        config.setMasterUrl("http://127.0.0.1:" + server.getAddress().getPort());
        config.setCaCertFile(null);
        try (var client = LocalKubernetesClient.build(config)) {
            client.namespaces().list();
            Path replacement = Files.writeString(credentials.resolve("token-v2"), "rotated-projected-token\n");
            Path link = credentials.resolve("next-token");
            Files.createSymbolicLink(link, replacement.getFileName());
            Files.move(link, token, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            client.namespaces().list();
            assertEquals(List.of("Bearer " + "first-projected-token",
                    "Bearer " + "rotated-projected-token"), authorization);
            Files.delete(token);
            assertInvalid(() -> config.getOauthTokenProvider().getToken());
            assertThrows(RuntimeException.class, () -> client.namespaces().list());
            assertEquals(2, authorization.size());
        } finally {
            server.stop(0);
        }
    }

    private static void assertInvalid(org.junit.jupiter.api.function.Executable action) {
        var error = assertThrows(ProvisioningException.class, action);
        assertFalse(error.retryable());
        assertNull(error.getCause());
        assertFalse(error.getMessage().contains("projected-credentials-"));
        assertFalse(error.getMessage().contains("first-projected-token"));
    }
}

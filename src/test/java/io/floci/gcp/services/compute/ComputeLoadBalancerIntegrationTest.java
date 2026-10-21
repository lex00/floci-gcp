package io.floci.gcp.services.compute;

import com.sun.net.httpserver.HttpServer;
import io.floci.gcp.core.tls.CertificateGenerator;
import io.floci.gcp.services.cloudrun.CloudRunService;
import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeInstance;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import jakarta.inject.Inject;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@QuarkusTest
class ComputeLoadBalancerIntegrationTest extends ComputeTestSupport {
    @InjectMock
    CloudRunService cloudRun;

    @Inject
    ComputeLoadBalancerDataPlane dataPlane;

    @Test void serverlessNegLoadBalancerServesTheCloudRunService() throws Exception {
        AtomicReference<String> seenPath = new AtomicReference<>(), seenHost = new AtomicReference<>();
        HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            seenPath.set(exchange.getRequestURI().toString());
            seenHost.set(exchange.getRequestHeaders().getFirst("X-Forwarded-Host"));
            byte[] body = "hello from web".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        backend.start();
        try {
            String root = root(), project = root.substring(root.lastIndexOf('/') + 1);
            when(cloudRun.readyRuntime(anyString())).thenReturn(Optional.empty());
            when(cloudRun.readyRuntime("projects/" + project + "/locations/us-central1/services/web")).thenReturn(Optional.of(
                    new CloudRunRuntimeInstance(project, "us-central1", "web", "web-00001", "image", "container", 8080, null,
                            "127.0.0.1", backend.getAddress().getPort(), "https://web.run.app", "READY", 0, 0, null, 5000)));
            int port = freePort();
            done(root, post(root + "/regions/us-central1/networkEndpointGroups", Map.of("name", "neg", "networkEndpointType", "SERVERLESS", "cloudRun", Map.of("service", "web"))));
            post(root + "/regions/us-central1/networkEndpointGroups/neg/attachNetworkEndpoints", Map.of("networkEndpoints", List.of(Map.of("ipAddress", "10.0.0.1", "port", 80)))).then().statusCode(400);
            done(root, post(root + "/global/healthChecks", Map.of("name", "health", "type", "HTTP")));
            Map<String,Object> serverless = Map.of("group", "regions/us-central1/networkEndpointGroups/neg");
            post(root + "/global/backendServices", Map.of("name", "checked", "loadBalancingScheme", "EXTERNAL_MANAGED", "protocol", "HTTP",
                    "healthChecks", List.of("global/healthChecks/health"), "backends", List.of(serverless))).then().statusCode(400);
            done(root, post(root + "/global/backendServices", Map.of("name", "web", "loadBalancingScheme", "EXTERNAL_MANAGED", "protocol", "HTTP", "backends", List.of(serverless))));
            done(root, post(root + "/global/urlMaps", Map.of("name", "web", "defaultService", "global/backendServices/web")));
            done(root, post(root + "/global/targetHttpProxies", Map.of("name", "web", "urlMap", "global/urlMaps/web")));
            done(root, post(root + "/global/addresses", Map.of("name", "web")));
            done(root, post(root + "/global/forwardingRules", Map.of("name", "web", "loadBalancingScheme", "EXTERNAL_MANAGED",
                    "portRange", Integer.toString(port), "IPAddress", "global/addresses/web", "target", "global/targetHttpProxies/web")));
            assertEquals("TCP", given().get(root + "/global/forwardingRules/web").jsonPath().getString("IPProtocol"));

            HttpResponse<String> response = getWhenListening(port, "/greeting?lang=en");
            assertEquals(200, response.statusCode());
            assertEquals("hello from web", response.body());
            assertEquals("/greeting?lang=en", seenPath.get());
            assertEquals("localhost:" + port, seenHost.get());

            done(root, given().delete(root + "/global/forwardingRules/web"));
            for (int i = 0; i < 100 && dataPlane.listening(port); i++) { Thread.sleep(50); }
            assertFalse(dataPlane.listening(port));
        } finally {
            backend.stop(0);
        }
    }

    @Test void serverlessNegRequiresOneTarget() throws Exception {
        String root = root(), neg = root + "/regions/us-central1/networkEndpointGroups";
        post(neg, Map.of("name", "none", "networkEndpointType", "SERVERLESS")).then().statusCode(400);
        post(neg, Map.of("name", "empty", "networkEndpointType", "SERVERLESS", "cloudRun", Map.of())).then().statusCode(400);
        post(neg, Map.of("name", "port", "networkEndpointType", "SERVERLESS", "cloudRun", Map.of("service", "web"), "defaultPort", 80)).then().statusCode(400);
        post(root + "/global/networkEndpointGroups", Map.of("name", "global", "networkEndpointType", "SERVERLESS", "cloudRun", Map.of("service", "web"))).then().statusCode(501);
        done(root, post(neg, Map.of("name", "mask", "networkEndpointType", "SERVERLESS", "cloudRun", Map.of("urlMask", "<service>.example.com"))));
    }

    @Test void httpsProxyServesTlsBehindCloudArmor() throws Exception {
        AtomicReference<String> seenProto = new AtomicReference<>();
        HttpServer backend = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        backend.createContext("/", exchange -> {
            seenProto.set(exchange.getRequestHeaders().getFirst("X-Forwarded-Proto"));
            byte[] body = "secure web".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        backend.start();
        try {
            String root = root(), project = root.substring(root.lastIndexOf('/') + 1);
            when(cloudRun.readyRuntime(anyString())).thenReturn(Optional.empty());
            when(cloudRun.readyRuntime("projects/" + project + "/locations/us-central1/services/web")).thenReturn(Optional.of(
                    new CloudRunRuntimeInstance(project, "us-central1", "web", "web-00001", "image", "container", 8080, null,
                            "127.0.0.1", backend.getAddress().getPort(), "https://web.run.app", "READY", 0, 0, null, 5000)));
            CertificateGenerator.GeneratedCertificate pem = new CertificateGenerator().generateCertificate(List.of("localhost", "127.0.0.1"));
            done(root, post(root + "/global/sslCertificates", Map.of("name", "own", "certificate", pem.certificatePem(), "privateKey", pem.privateKeyPem())));
            JsonPath own = given().get(root + "/global/sslCertificates/own").jsonPath();
            assertEquals("SELF_MANAGED", own.getString("type"));
            assertNull(own.get("privateKey"));
            assertEquals(List.of("localhost", "127.0.0.1"), own.getList("subjectAlternativeNames"));
            assertNotNull(own.getString("expireTime"));
            done(root, post(root + "/global/sslCertificates", Map.of("name", "managed", "managed", Map.of("domains", List.of("app.example.com")))));
            JsonPath managed = given().get(root + "/global/sslCertificates/managed").jsonPath();
            assertEquals("ACTIVE", managed.getString("managed.status"));
            assertEquals("ACTIVE", managed.getString("managed.domainStatus.'app.example.com'"));
            assertNull(given().get(root + "/global/sslCertificates").jsonPath().get("items[0].privateKey"));
            post(root + "/global/sslCertificates", Map.of("name", "bad", "certificate", "nope", "privateKey", pem.privateKeyPem())).then().statusCode(400);

            done(root, post(root + "/global/securityPolicies", Map.of("name", "armor", "rules", List.of(Map.of("priority", 1000, "action", "deny(403)",
                    "match", Map.of("versionedExpr", "SRC_IPS_V1", "config", Map.of("srcIpRanges", List.of("127.0.0.1/32"))))))));
            JsonPath armor = given().get(root + "/global/securityPolicies/armor").jsonPath();
            assertEquals(2, armor.getList("rules").size());
            assertEquals("2147483647", armor.getString("rules[1].priority"));
            given().queryParam("priority", 2147483647).contentType("application/json").body(Map.of()).post(root + "/global/securityPolicies/armor/removeRule").then().statusCode(400);

            done(root, post(root + "/regions/us-central1/networkEndpointGroups", Map.of("name", "neg", "networkEndpointType", "SERVERLESS", "cloudRun", Map.of("service", "web"))));
            done(root, post(root + "/global/backendServices", Map.of("name", "web", "loadBalancingScheme", "EXTERNAL_MANAGED", "protocol", "HTTP",
                    "securityPolicy", "global/securityPolicies/armor", "backends", List.of(Map.of("group", "regions/us-central1/networkEndpointGroups/neg")))));
            given().delete(root + "/global/securityPolicies/armor").then().statusCode(400);
            done(root, post(root + "/global/urlMaps", Map.of("name", "web", "defaultService", "global/backendServices/web")));
            done(root, post(root + "/global/targetHttpsProxies", Map.of("name", "web", "urlMap", "global/urlMaps/web", "sslCertificates", List.of("global/sslCertificates/own"))));
            done(root, post(root + "/global/addresses", Map.of("name", "web")));
            int port = freePort();
            done(root, post(root + "/global/forwardingRules", Map.of("name", "web", "loadBalancingScheme", "EXTERNAL_MANAGED",
                    "portRange", Integer.toString(port), "IPAddress", "global/addresses/web", "target", "global/targetHttpsProxies/web")));

            HttpClient client = HttpClient.newBuilder().sslContext(trustAll()).build();
            String url = "https://127.0.0.1:" + port + "/";
            assertEquals(403, getWhenListening(client, url).statusCode());
            done(root, given().queryParam("priority", 1000).contentType("application/json").body(Map.of("action", "allow"))
                    .post(root + "/global/securityPolicies/armor/patchRule"));
            assertEquals("allow", given().queryParam("priority", 1000).get(root + "/global/securityPolicies/armor/getRule").jsonPath().getString("action"));
            HttpResponse<String> allowed = getWhenListening(client, url);
            assertEquals(200, allowed.statusCode());
            assertEquals("secure web", allowed.body());
            assertEquals("https", seenProto.get());

            done(root, post(root + "/targetHttpsProxies/web/setSslCertificates", Map.of("sslCertificates", List.of("global/sslCertificates/managed"))));
            assertTrue(given().get(root + "/global/targetHttpsProxies/web").jsonPath().getString("sslCertificates[0]").endsWith("/sslCertificates/managed"));
            String presented = "";
            for (int i = 0; i < 100 && !presented.contains("app.example.com"); i++) {
                presented = presentedNames(port);
                Thread.sleep(50);
            }
            assertTrue(presented.contains("app.example.com"), presented);
            String fingerprint = given().get(root + "/global/backendServices/web").jsonPath().getString("fingerprint");
            done(root, post(root + "/global/backendServices/web/setSecurityPolicy", Map.of("securityPolicy", "")));
            assertNull(given().get(root + "/global/backendServices/web").jsonPath().get("securityPolicy"));
            assertNotEquals(fingerprint, given().get(root + "/global/backendServices/web").jsonPath().getString("fingerprint"));
            done(root, given().delete(root + "/global/securityPolicies/armor"));
            done(root, given().delete(root + "/global/forwardingRules/web"));
        } finally {
            backend.stop(0);
        }
    }

    private static String presentedNames(int port) throws Exception {
        try (SSLSocket socket = (SSLSocket) trustAll().getSocketFactory().createSocket("127.0.0.1", port)) {
            socket.startHandshake();
            return String.valueOf(((X509Certificate) socket.getSession().getPeerCertificates()[0]).getSubjectAlternativeNames());
        } catch (IOException e) {
            return "";
        }
    }

    private static SSLContext trustAll() throws Exception {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] chain, String authType) { }
            public void checkServerTrusted(X509Certificate[] chain, String authType) { }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        return context;
    }

    private static HttpResponse<String> getWhenListening(int port, String path) throws Exception {
        return getWhenListening(HttpClient.newHttpClient(), "http://localhost:" + port + path);
    }

    private static HttpResponse<String> getWhenListening(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url)).build();
        IOException last = null;
        for (int i = 0; i < 100; i++) {
            try {
                return client.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                last = e;
                Thread.sleep(50);
            }
        }
        throw last;
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}

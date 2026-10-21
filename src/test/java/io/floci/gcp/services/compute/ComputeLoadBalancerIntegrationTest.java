package io.floci.gcp.services.compute;

import com.sun.net.httpserver.HttpServer;
import io.floci.gcp.services.cloudrun.CloudRunService;
import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeInstance;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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

    private static HttpResponse<String> getWhenListening(int port, String path) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).build();
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

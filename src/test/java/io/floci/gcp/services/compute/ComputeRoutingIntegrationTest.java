package io.floci.gcp.services.compute;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;
import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ComputeRoutingIntegrationTest extends ComputeTestSupport {
    @Test void routingMembershipAndDependencyCleanup() throws Exception {
        String root = root(), zone = "/zones/us-central1-a";
        done(root, post(root + "/global/networks", Map.of("name", "net", "autoCreateSubnetworks", false)));
        done(root, post(root + "/regions/us-central1/subnetworks", Map.of("name", "subnet", "network", "global/networks/net", "ipCidrRange", "10.8.0.0/24")));
        done(root, post(root + zone + "/instances", Map.of("name", "vm", "machineType", "zones/us-central1-a/machineTypes/n2-standard-4",
                "networkInterfaces", List.of(Map.of("subnetwork", "regions/us-central1/subnetworks/subnet")),
                "disks", List.of(Map.of("boot", true, "autoDelete", true, "initializeParams", Map.of("diskSizeGb", "20"))))));
        done(root, post(root + zone + "/networkEndpointGroups", Map.of("name", "neg", "networkEndpointType", "GCE_VM_IP_PORT", "network", "global/networks/net", "subnetwork", "regions/us-central1/subnetworks/subnet")));
        Map<String,Object> endpoints = Map.of("networkEndpoints", List.of(Map.of("instance", "vm", "port", 8080)));
        done(root, post(root + zone + "/networkEndpointGroups/neg/attachNetworkEndpoints", endpoints));
        assertEquals(8080, post(root + zone + "/networkEndpointGroups/neg/listNetworkEndpoints", Map.of()).jsonPath().getInt("items[0].networkEndpoint.port"));
        done(root, post(root + "/global/healthChecks", Map.of("name", "health", "type", "HTTP", "httpHealthCheck", Map.of("port", 8081))));
        done(root, post(root + "/global/backendServices", Map.of("name", "backend", "loadBalancingScheme", "EXTERNAL_MANAGED", "healthChecks", List.of("global/healthChecks/health"), "backends", List.of(Map.of("group", "zones/us-central1-a/networkEndpointGroups/neg", "balancingMode", "RATE", "maxRatePerEndpoint", 100)))));
        done(root, post(root + "/global/urlMaps", Map.of("name", "routes", "defaultService", "global/backendServices/backend", "hostRules", List.of(Map.of("hosts", List.of("*"), "pathMatcher", "routes")),
                "pathMatchers", List.of(Map.of("name", "routes", "defaultService", "global/backendServices/backend", "pathRules", List.of(Map.of("paths", List.of("/route-one/*", "/route-two/*"), "service", "global/backendServices/backend")))))));
        done(root, post(root + "/global/targetHttpProxies", Map.of("name", "proxy", "urlMap", "global/urlMaps/routes")));
        done(root, post(root + "/global/addresses", Map.of("name", "frontend")));
        done(root, post(root + "/global/forwardingRules", Map.of("name", "frontend", "target", "global/targetHttpProxies/proxy", "IPAddress", "global/addresses/frontend", "IPProtocol", "TCP", "portRange", "80", "loadBalancingScheme", "EXTERNAL_MANAGED")));
        given().delete(root + "/global/backendServices/backend").then().statusCode(400);
        given().delete(root + zone + "/instances/vm").then().statusCode(400);
        assertEquals(1, given().get(root + zone + "/disks/vm").jsonPath().getList("users").size());
        done(root, given().delete(root + "/global/forwardingRules/frontend"));
        done(root, given().delete(root + "/global/addresses/frontend"));
        done(root, given().delete(root + "/global/targetHttpProxies/proxy"));
        String fingerprint = given().get(root + "/global/urlMaps/routes").jsonPath().getString("fingerprint");
        done(root, given().contentType("application/json").body(Map.of("fingerprint", fingerprint, "pathMatchers", List.of(Map.of("name", "routes", "defaultService", "global/backendServices/backend", "pathRules", List.of(Map.of("paths", List.of("/route-two/*"), "service", "global/backendServices/backend")))))).patch(root + "/global/urlMaps/routes"));
        assertEquals("/route-two/*", given().get(root + "/global/urlMaps/routes").jsonPath().getString("pathMatchers[0].pathRules[0].paths[0]"));
        given().get(root + "/global/backendServices/backend").then().statusCode(200);
        done(root, given().delete(root + "/global/urlMaps/routes"));
        done(root, given().delete(root + "/global/backendServices/backend"));
        done(root, given().delete(root + "/global/healthChecks/health"));
        done(root, post(root + zone + "/networkEndpointGroups/neg/detachNetworkEndpoints", endpoints));
        done(root, given().delete(root + zone + "/networkEndpointGroups/neg"));
        done(root, given().delete(root + zone + "/instances/vm"));
        given().get(root + zone + "/disks/vm").then().statusCode(404);
    }
    @Test void globalInternetEndpointGroupsTakeEndpointsWithoutInstance() throws Exception {
        String root = root(), neg = root + "/global/networkEndpointGroups";
        done(root, post(neg, Map.of("name", "ip", "networkEndpointType", "INTERNET_IP_PORT")));
        Map<String,Object> ip = Map.of("networkEndpoints", List.of(Map.of("ipAddress", "10.0.0.1", "port", 80)));
        done(root, post(neg + "/ip/attachNetworkEndpoints", ip));
        JsonPath listed = post(neg + "/ip/listNetworkEndpoints", Map.of()).jsonPath();
        assertEquals("10.0.0.1", listed.getString("items[0].networkEndpoint.ipAddress"));
        assertNull(listed.get("items[0].networkEndpoint.fqdn"));
        assertEquals(1, given().get(neg + "/ip").jsonPath().getInt("size"));
        post(neg + "/ip/attachNetworkEndpoints", Map.of("networkEndpoints", List.of(Map.of("fqdn", "example.com", "port", 80)))).then().statusCode(400);
        post(neg + "/ip/attachNetworkEndpoints", Map.of("networkEndpoints", List.of(Map.of("ipAddress", "not-an-ip", "port", 80)))).then().statusCode(400);
        done(root, post(neg, Map.of("name", "fqdn", "networkEndpointType", "INTERNET_FQDN_PORT", "defaultPort", 443)));
        done(root, post(neg + "/fqdn/attachNetworkEndpoints", Map.of("networkEndpoints", List.of(Map.of("fqdn", "backend.example.com")))));
        assertEquals(443, post(neg + "/fqdn/listNetworkEndpoints", Map.of()).jsonPath().getInt("items[0].networkEndpoint.port"));
        post(neg + "/fqdn/attachNetworkEndpoints", Map.of("networkEndpoints", List.of(Map.of("ipAddress", "10.0.0.1", "port", 80)))).then().statusCode(400);
        done(root, post(root + "/global/healthChecks", Map.of("name", "health", "type", "HTTP")));
        Map<String,Object> backend = Map.of("group", "global/networkEndpointGroups/ip");
        post(root + "/global/backendServices", Map.of("name", "checked", "healthChecks", List.of("global/healthChecks/health"), "backends", List.of(backend))).then().statusCode(400);
        done(root, post(root + "/global/backendServices", Map.of("name", "internet", "backends", List.of(backend))));
        done(root, given().delete(root + "/global/backendServices/internet"));
        done(root, post(neg + "/ip/detachNetworkEndpoints", ip));
        assertEquals(0, given().get(neg + "/ip").jsonPath().getInt("size"));
        post(neg, Map.of("name", "vm", "networkEndpointType", "GCE_VM_IP_PORT")).then().statusCode(501);
        post(neg, Map.of("name", "net", "networkEndpointType", "INTERNET_IP_PORT", "network", "global/networks/default")).then().statusCode(400);
    }
    @Test void regionalInternetEndpointGroups() throws Exception {
        String root = root(), neg = root + "/regions/us-central1/networkEndpointGroups";
        done(root, post(root + "/global/networks", Map.of("name", "net", "autoCreateSubnetworks", false)));
        done(root, post(neg, Map.of("name", "fqdn", "networkEndpointType", "INTERNET_FQDN_PORT", "network", "global/networks/net")));
        done(root, post(neg + "/fqdn/attachNetworkEndpoints", Map.of("networkEndpoints", List.of(Map.of("fqdn", "backend.example.com", "port", 443)))));
        assertEquals("backend.example.com", post(neg + "/fqdn/listNetworkEndpoints", Map.of()).jsonPath().getString("items[0].networkEndpoint.fqdn"));
        assertTrue(given().get(neg + "/fqdn").jsonPath().getString("region").endsWith("/regions/us-central1"));
        done(root, post(neg, Map.of("name", "ip", "networkEndpointType", "INTERNET_IP_PORT")));
        done(root, post(neg + "/ip/attachNetworkEndpoints", Map.of("networkEndpoints", List.of(Map.of("ipAddress", "2001:db8::1", "port", 80)))));
        done(root, given().delete(neg + "/ip"));
        given().get(neg + "/ip").then().statusCode(404);
    }
    @Test void zonalHybridEndpointGroupTakesEndpointsWithoutInstance() throws Exception {
        String root = root(), neg = root + "/zones/us-central1-a/networkEndpointGroups";
        done(root, post(root + "/global/networks", Map.of("name", "net", "autoCreateSubnetworks", false)));
        done(root, post(neg, Map.of("name", "hybrid", "networkEndpointType", "NON_GCP_PRIVATE_IP_PORT", "network", "global/networks/net", "defaultPort", 8080)));
        Map<String,Object> endpoints = Map.of("networkEndpoints", List.of(Map.of("ipAddress", "192.168.1.5")));
        done(root, post(neg + "/hybrid/attachNetworkEndpoints", endpoints));
        JsonPath listed = post(neg + "/hybrid/listNetworkEndpoints", Map.of()).jsonPath();
        assertEquals("192.168.1.5", listed.getString("items[0].networkEndpoint.ipAddress"));
        assertEquals(8080, listed.getInt("items[0].networkEndpoint.port"));
        assertNull(listed.get("items[0].networkEndpoint.instance"));
        post(neg + "/hybrid/attachNetworkEndpoints", Map.of("networkEndpoints", List.of(Map.of("instance", "vm", "ipAddress", "192.168.1.6")))).then().statusCode(400);
        done(root, post(neg + "/hybrid/detachNetworkEndpoints", endpoints));
        done(root, given().delete(neg + "/hybrid"));
        post(neg, Map.of("name", "nonet", "networkEndpointType", "NON_GCP_PRIVATE_IP_PORT")).then().statusCode(400);
    }
}

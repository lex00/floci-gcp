package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ComputeLoadBalancerRoutingServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test void urlMapPicksHostThenLongestPathRule() throws Exception {
        JsonNode urlMap = JSON.readTree("""
                {"defaultService": "default",
                 "hostRules": [{"hosts": ["api.example.com"], "pathMatcher": "api"},
                               {"hosts": ["*.example.com"], "pathMatcher": "wild"},
                               {"hosts": ["*"], "pathMatcher": "any"}],
                 "pathMatchers": [
                   {"name": "api", "defaultService": "api-default",
                    "pathRules": [{"paths": ["/v1/*"], "service": "v1"}, {"paths": ["/v1/users/*"], "service": "users"},
                                  {"paths": ["/health"], "service": "health"}]},
                   {"name": "wild", "defaultService": "wild-default"},
                   {"name": "any", "defaultService": "any-default"}]}
                """);
        assertEquals("users", ComputeLoadBalancerDataPlane.service(urlMap, "api.example.com", "/v1/users/7"));
        assertEquals("v1", ComputeLoadBalancerDataPlane.service(urlMap, "api.example.com", "/v1/items"));
        assertEquals("health", ComputeLoadBalancerDataPlane.service(urlMap, "api.example.com", "/health"));
        assertEquals("api-default", ComputeLoadBalancerDataPlane.service(urlMap, "api.example.com", "/health/deep"));
        assertEquals("wild-default", ComputeLoadBalancerDataPlane.service(urlMap, "www.example.com", "/"));
        assertEquals("any-default", ComputeLoadBalancerDataPlane.service(urlMap, "other.test", "/"));
        assertEquals("default", ComputeLoadBalancerDataPlane.service(JSON.readTree("{\"defaultService\": \"default\"}"), "x", "/"));
    }

    @Test void hostNameDropsThePort() {
        assertEquals("example.com", ComputeLoadBalancerDataPlane.hostName("Example.com:8080"));
        assertEquals("::1", ComputeLoadBalancerDataPlane.hostName("[::1]:80"));
        assertEquals("", ComputeLoadBalancerDataPlane.hostName(null));
    }

    @Test void onlySinglePortRulesGetAListener() {
        ObjectNode rule = JSON.createObjectNode().put("portRange", "8080");
        assertEquals(8080, ComputeLoadBalancerDataPlane.listenerPort(rule));
        assertEquals(80, ComputeLoadBalancerDataPlane.listenerPort(rule.put("portRange", "80-80")));
        assertEquals(0, ComputeLoadBalancerDataPlane.listenerPort(rule.put("portRange", "80-81")));
    }
}

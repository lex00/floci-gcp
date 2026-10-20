package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class ComputeJsonValidationIntegrationTest extends ComputeTestSupport {
    private static final ObjectMapper JSON = new ObjectMapper();

    private void invalid(String path, ObjectNode body) {
        post(path, body.toString()).then().statusCode(400)
                .body("error.code", equalTo(400)).body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test void typesWithoutLabelsRejectLabelFields() throws Exception {
        String root = root();
        for (String field : List.of("labels", "labelFingerprint")) {
            JsonNode value = field.equals("labels") ? JSON.readTree("{\"env\":\"prod\"}") : JSON.readTree("\"abc\"");
            ObjectNode network = JSON.createObjectNode().put("name", "net").put("autoCreateSubnetworks", false);
            invalid(root + "/global/networks", network.set(field, value));
        }
        given().get(root + "/global/networks/net").then().statusCode(404);
        done(root, post(root + "/global/networks", Map.of("name", "net", "autoCreateSubnetworks", false)));
        invalid(root + "/regions/us-central1/subnetworks", (ObjectNode) JSON.readTree("""
                {"name":"subnet","network":"global/networks/net","ipCidrRange":"10.9.0.0/24","labels":{"env":"prod"}}
                """));
        given().get(root + "/regions/us-central1/subnetworks/subnet").then().statusCode(404);
        invalid(root + "/global/firewalls", (ObjectNode) JSON.readTree("""
                {"name":"fw","network":"global/networks/net","allowed":[{"IPProtocol":"tcp","ports":["22"]}],"labels":{"env":"prod"}}
                """));
        done(root, post(root + "/global/firewalls", Map.of("name", "fw", "network", "global/networks/net",
                "allowed", List.of(Map.of("IPProtocol", "tcp", "ports", List.of("22"))))));
        given().contentType("application/json").body("{\"labels\":{\"env\":\"prod\"}}")
                .patch(root + "/global/firewalls/fw").then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));
        assertFalse(given().get(root + "/global/firewalls/fw").jsonPath().getMap("$").containsKey("labels"));
        done(root, post(root + "/regions/us-central1/addresses", Map.of("name", "addr", "labels", Map.of("env", "prod"))));
        assertEquals("prod", given().get(root + "/regions/us-central1/addresses/addr").jsonPath().getString("labels.env"));
    }

    @Test void malformedHealthChecksDoNotCreateResourcesOrOperations() throws Exception {
        String root = root();
        for (String value : List.of("null", "false", "42", "\"bad\"", "[]")) {
            invalid(root + "/global/healthChecks", (ObjectNode) JSON.readTree(
                    "{\"name\":\"health\",\"type\":\"HTTP\",\"httpHealthCheck\":" + value + "}"));
        }
        given().get(root + "/global/healthChecks/health").then().statusCode(404);
        assertTrue(given().get(root + "/global/operations").jsonPath().getList("items").isEmpty());
        done(root, post(root + "/global/healthChecks", Map.of("name", "health", "type", "HTTP")));
        String before = given().get(root + "/global/healthChecks/health").asString();
        given().contentType("application/json").body("{\"httpHealthCheck\":null}")
                .patch(root + "/global/healthChecks/health").then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
        assertEquals(JSON.readTree(before), JSON.readTree(given().get(root + "/global/healthChecks/health").asString()));
    }

    @Test void malformedRoutingArraysAndEntriesAreInvalidArguments() throws Exception {
        String root = root();
        done(root, post(root + "/global/healthChecks", Map.of("name", "health", "type", "HTTP")));
        for (String value : List.of("null", "{}", "\"bad\"", "[null]", "[42]", "[[]]")) {
            invalid(root + "/global/backendServices", (ObjectNode) JSON.readTree(
                    "{\"name\":\"backend\",\"healthChecks\":[\"global/healthChecks/health\"],\"backends\":" + value + "}"));
        }
        done(root, post(root + "/global/backendServices", Map.of("name", "backend", "healthChecks", List.of("global/healthChecks/health"))));
        for (String field : List.of("pathMatchers", "hostRules")) {
            for (String value : List.of("null", "{}", "\"bad\"", "[null]", "[[]]")) {
                invalid(root + "/global/urlMaps", (ObjectNode) JSON.readTree(
                        "{\"name\":\"routes\",\"defaultService\":\"global/backendServices/backend\",\"" + field + "\":" + value + "}"));
            }
        }
        invalid(root + "/global/urlMaps", (ObjectNode) JSON.readTree("""
                {"name":"routes","defaultService":"global/backendServices/backend","pathMatchers":[
                  {"name":"paths","defaultService":"global/backendServices/backend","pathRules":[null]}]}
                """));
        given().get(root + "/global/urlMaps/routes").then().statusCode(404);
    }

    @Test void malformedInstanceObjectsLeaveNoDisksOrAddressClaims() throws Exception {
        String root = root(), zone = "/zones/us-central1-a";
        done(root, post(root + "/global/networks", Map.of("name", "net", "autoCreateSubnetworks", false)));
        done(root, post(root + "/regions/us-central1/subnetworks", Map.of("name", "subnet", "network", "global/networks/net", "ipCidrRange", "10.8.0.0/24")));
        ObjectNode valid = (ObjectNode) JSON.readTree("""
                {"name":"vm","machineType":"zones/us-central1-a/machineTypes/n2-standard-4",
                 "networkInterfaces":[{"subnetwork":"regions/us-central1/subnetworks/subnet"}],
                 "disks":[{"boot":true,"autoDelete":true,"initializeParams":{"diskSizeGb":"20"}}]}
                """);
        for (String field : List.of("metadata", "tags", "scheduling")) {
            for (String value : List.of("null", "[]", "\"bad\"")) {
                ObjectNode body = valid.deepCopy(); body.set(field, JSON.readTree(value)); invalid(root + zone + "/instances", body);
            }
        }
        for (String field : List.of("networkInterfaces", "disks", "guestAccelerators")) {
            for (String value : List.of("null", "{}", "\"bad\"", "[null]", "[[]]")) {
                ObjectNode body = valid.deepCopy(); body.set(field, JSON.readTree(value)); invalid(root + zone + "/instances", body);
            }
        }
        for (String value : List.of("null", "[]", "\"bad\"")) {
            ObjectNode body = valid.deepCopy(); ((ObjectNode) body.path("disks").get(0)).set("initializeParams", JSON.readTree(value));
            invalid(root + zone + "/instances", body);
        }
        for (String value : List.of("null", "{}", "[null]", "[[]]")) {
            ObjectNode body = valid.deepCopy(); ((ObjectNode) body.path("networkInterfaces").get(0)).set("accessConfigs", JSON.readTree(value));
            invalid(root + zone + "/instances", body);
        }
        assertTrue(given().get(root + zone + "/disks").jsonPath().getList("items").isEmpty());
        given().get(root + zone + "/instances/vm").then().statusCode(404);
        done(root, post(root + zone + "/instances", valid.toString()));
        assertEquals("10.8.0.2", given().get(root + zone + "/instances/vm").jsonPath().getString("networkInterfaces[0].networkIP"));
    }
}

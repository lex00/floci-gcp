package io.floci.gcp.services.cloudrun;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class CloudRunVpcAccessRestIntegrationTest {

    private static final String PROJECT = "run-vpc-it";
    private static final String SERVICES = "/v2/projects/" + PROJECT + "/locations/us-central1/services";
    private static final String COMPUTE = "/compute/v1/projects/" + PROJECT;

    private static boolean networked;

    // RestAssured has the test port only once the Quarkus app is up, so this cannot be @BeforeAll.
    @BeforeEach
    void network() {
        if (networked) {
            return;
        }
        networked = true;
        given().contentType("application/json").body(Map.of("name", "vpc", "autoCreateSubnetworks", false))
                .post(COMPUTE + "/global/networks").then().statusCode(200);
        given().contentType("application/json").body(Map.of("name", "other", "autoCreateSubnetworks", false))
                .post(COMPUTE + "/global/networks").then().statusCode(200);
        given().contentType("application/json").body(Map.of("name", "run", "network", "global/networks/vpc", "ipCidrRange", "10.20.0.0/24"))
                .post(COMPUTE + "/regions/us-central1/subnetworks").then().statusCode(200);
        given().contentType("application/json").body(Map.of("name", "vpc", "network", "global/networks/vpc", "ipCidrRange", "10.21.0.0/24"))
                .post(COMPUTE + "/regions/us-central1/subnetworks").then().statusCode(200);
        given().contentType("application/json").body(Map.of("name", "east", "network", "global/networks/vpc", "ipCidrRange", "10.22.0.0/24"))
                .post(COMPUTE + "/regions/us-east1/subnetworks").then().statusCode(200);
    }

    private static Map<String, Object> body(Map<String, Object> nic) {
        return Map.of("template", Map.of("vpcAccess", Map.of("egress", "ALL_TRAFFIC", "networkInterfaces", List.of(nic)),
                "containers", List.of(Map.of("image", "traefik/whoami:v1.10"))));
    }

    private static void refused(String id, Map<String, Object> nic, String message) {
        given().contentType("application/json").queryParam("serviceId", id).body(body(nic))
                .when().post(SERVICES)
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"))
                .body("error.message", containsString(message));
        given().when().get(SERVICES + "/" + id).then().statusCode(404);
    }

    @Test
    void createRefusesMissingNetworksAndSubnetworks() {
        refused("missing-network", Map.of("network", "nonexistent", "subnetwork", "run"), "VPC network nonexistent does not exist");
        refused("missing-subnet", Map.of("network", "vpc", "subnetwork", "nope"), "Subnetwork nope does not exist in region us-central1");
        refused("other-region", Map.of("subnetwork", "projects/" + PROJECT + "/regions/us-east1/subnetworks/east"), "is not in region us-central1");
        refused("wrong-network", Map.of("network", "other", "subnetwork", "run"), "is not in network other");
        refused("no-default-subnet", Map.of("network", "other"), "Subnetwork other does not exist");
    }

    @Test
    void createAcceptsEveryReferenceForm() {
        String full = "https://www.googleapis.com/compute/v1/projects/" + PROJECT;
        List<Map<String, Object>> nics = List.of(
                Map.of("network", "vpc", "subnetwork", "run"),
                Map.of("network", "projects/" + PROJECT + "/global/networks/vpc", "subnetwork", "projects/" + PROJECT + "/regions/us-central1/subnetworks/run"),
                Map.of("network", full + "/global/networks/vpc", "subnetwork", full + "/regions/us-central1/subnetworks/run"),
                Map.of("subnetwork", "run"),
                Map.of("network", "vpc"));
        for (int i = 0; i < nics.size(); i++) {
            given().contentType("application/json").queryParam("serviceId", "form-" + i).body(body(nics.get(i)))
                    .when().post(SERVICES)
                    .then().statusCode(200);
        }
        given().when().get(SERVICES + "/form-0")
                .then().statusCode(200)
                .body("template.vpcAccess.networkInterfaces[0].network", equalTo("vpc"))
                .body("template.vpcAccess.egress", equalTo("ALL_TRAFFIC"));
    }

    @Test
    void updateRefusesAMissingSubnetwork() {
        given().contentType("application/json").queryParam("serviceId", "updated").body(body(Map.of("network", "vpc", "subnetwork", "run")))
                .when().post(SERVICES).then().statusCode(200);
        given().contentType("application/json").body(body(Map.of("network", "vpc", "subnetwork", "gone")))
                .when().patch(SERVICES + "/updated")
                .then().statusCode(400)
                .body("error.message", containsString("Subnetwork gone does not exist"));
        given().when().get(SERVICES + "/updated")
                .then().statusCode(200)
                .body("template.vpcAccess.networkInterfaces[0].subnetwork", equalTo("run"));
    }
}

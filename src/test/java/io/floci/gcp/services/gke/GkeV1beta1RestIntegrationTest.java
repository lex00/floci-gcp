package io.floci.gcp.services.gke;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/**
 * The container API under {@code v1beta1}, the version {@code google-beta} and Pulumi call, in
 * path mode and host mode, and the 404 for {@code /container/...} paths no route serves.
 */
@QuarkusTest
class GkeV1beta1RestIntegrationTest {

    private static final String PROJECT = "gke-v1beta1-it";
    private static final String LOCATION = "us-central1";
    private static final String BASE = "/container/v1beta1/projects/" + PROJECT + "/locations/" + LOCATION;

    @Test
    void clustersAndNodePoolsAreServedUnderV1beta1() {
        given().contentType("application/json")
                .body("{\"cluster\":{\"name\":\"beta-c1\",\"podSecurityPolicyConfig\":{\"enabled\":false}}}")
                .when().post(BASE + "/clusters")
                .then().statusCode(200).body("operationType", equalTo("CREATE_CLUSTER"));

        given().when().get(BASE + "/clusters")
                .then().statusCode(200).body("clusters.name", hasItem("beta-c1"));
        given().when().get(BASE + "/clusters/beta-c1")
                .then().statusCode(200).body("name", equalTo("beta-c1"));
        given().when().get("/container/v1/projects/" + PROJECT + "/locations/" + LOCATION + "/clusters/beta-c1")
                .then().statusCode(200).body("name", equalTo("beta-c1"));

        given().contentType("application/json")
                .body("{\"nodePool\":{\"name\":\"beta-pool\",\"initialNodeCount\":1}}")
                .when().post(BASE + "/clusters/beta-c1/nodePools")
                .then().statusCode(200);
        given().when().get(BASE + "/clusters/beta-c1/nodePools/beta-pool")
                .then().statusCode(200).body("name", equalTo("beta-pool"));
        given().when().get(BASE + "/operations")
                .then().statusCode(200);
        given().when().get("/container/v1beta1/projects/" + PROJECT + "/aggregated/usableSubnetworks")
                .then().statusCode(200);
    }

    @Test
    void hostModeRewritesV1beta1Paths() {
        given().header("Host", "container.localhost")
                .when().get("/v1beta1/projects/" + PROJECT + "/locations/europe-west1/clusters")
                .then().statusCode(200);
        given().header("Host", "container.localhost").contentType("application/json")
                .body("{\"cluster\":{\"name\":\"beta-host\"}}")
                .when().post("/v1beta1/projects/" + PROJECT + "/locations/europe-west1/clusters")
                .then().statusCode(200).body("operationType", equalTo("CREATE_CLUSTER"));
    }

    @Test
    void v1beta1FieldsFlociGcpLacksAreRejectedByName() {
        given().contentType("application/json")
                .body("{\"cluster\":{\"name\":\"beta-tpu\",\"tpuConfig\":{\"enabled\":true}}}")
                .when().post(BASE + "/clusters")
                .then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"))
                .body("error.message", containsString("cluster.tpuConfig"));
        given().when().get(BASE + "/clusters/beta-tpu").then().statusCode(404);

        given().contentType("application/json")
                .body("{\"cluster\":{\"name\":\"beta-np\",\"nodePools\":[{\"name\":\"p\","
                        + "\"config\":{\"ephemeralStorageConfig\":{\"localSsdCount\":1}}}]}}")
                .when().post(BASE + "/clusters")
                .then().statusCode(400)
                .body("error.message", containsString("cluster.nodePools[0].config.ephemeralStorageConfig"));

        given().contentType("application/json")
                .body("{\"cluster\":{\"name\":\"beta-upd\"}}")
                .when().post(BASE + "/clusters").then().statusCode(200);
        given().contentType("application/json")
                .body("{\"update\":{\"desiredAddonsConfig\":{\"istioConfig\":{\"disabled\":true}}}}")
                .when().put(BASE + "/clusters/beta-upd")
                .then().statusCode(400)
                .body("error.message", containsString("update.desiredAddonsConfig.istioConfig"));

        given().contentType("application/json")
                .body("{\"cluster\":{\"name\":\"v1-tpu\",\"tpuConfig\":{\"enabled\":true}}}")
                .when().post("/container/v1/projects/" + PROJECT + "/locations/" + LOCATION + "/clusters")
                .then().statusCode(200);
    }

    @Test
    void unmatchedContainerPathsAnswer404NamingThePath() {
        given().contentType("application/json").body("{\"cluster\":{\"name\":\"c\"}}")
                .when().post("/container/v2/projects/" + PROJECT + "/locations/" + LOCATION + "/clusters")
                .then().statusCode(404)
                .body("error.status", equalTo("NOT_FOUND"))
                .body("error.message", containsString("/container/v2/projects/" + PROJECT));
        given().when().get(BASE + "/clusters/beta-c1/nosuch/thing")
                .then().statusCode(404)
                .body("error.message", containsString("/clusters/beta-c1/nosuch/thing"));
        given().header("Host", "container.localhost")
                .when().get("/v1beta1/projects/" + PROJECT + "/nosuch")
                .then().statusCode(404)
                .body("error.message", containsString("/container/v1beta1/projects/" + PROJECT + "/nosuch"));
    }
}

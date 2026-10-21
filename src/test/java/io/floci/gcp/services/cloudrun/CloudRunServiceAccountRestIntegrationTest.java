package io.floci.gcp.services.cloudrun;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

@QuarkusTest
class CloudRunServiceAccountRestIntegrationTest {

    private static final String PROJECT = "run-sa-it";
    private static final String SERVICES = "/v2/projects/" + PROJECT + "/locations/us-central1/services";

    private static String body(String serviceAccount) {
        return "{\"template\":{\"serviceAccount\":\"" + serviceAccount
                + "\",\"containers\":[{\"image\":\"traefik/whoami:v1.10\"}]}}";
    }

    @Test
    void createRefusesAServiceAccountThatDoesNotExist() {
        given().contentType("application/json").queryParam("serviceId", "missing")
                .body(body("nobody@" + PROJECT + ".iam.gserviceaccount.com"))
                .when().post(SERVICES)
                .then()
                .statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"))
                .body("error.message", containsString("nobody@" + PROJECT + ".iam.gserviceaccount.com does not exist"));
        given().when().get(SERVICES + "/missing").then().statusCode(404);
    }

    @Test
    void createAndUpdateAcceptAnExistingServiceAccountAndUpdateRefusesAMissingOne() {
        given().contentType("application/json")
                .body(Map.of("accountId", "runner"))
                .when().post("/v1/projects/" + PROJECT + "/serviceAccounts")
                .then().statusCode(200);
        String runner = "runner@" + PROJECT + ".iam.gserviceaccount.com";
        given().contentType("application/json").queryParam("serviceId", "web")
                .body(body(runner))
                .when().post(SERVICES)
                .then().statusCode(200);
        given().when().get(SERVICES + "/web")
                .then().statusCode(200)
                .body("template.serviceAccount", equalTo(runner));

        given().contentType("application/json")
                .body(body("ghost@" + PROJECT + ".iam.gserviceaccount.com"))
                .when().patch(SERVICES + "/web")
                .then().statusCode(400)
                .body("error.message", containsString("ghost@" + PROJECT + ".iam.gserviceaccount.com does not exist"));
        given().when().get(SERVICES + "/web")
                .then().statusCode(200)
                .body("template.serviceAccount", equalTo(runner));
    }

    @Test
    void defaultComputeAccountIsNotChecked() {
        given().contentType("application/json").queryParam("serviceId", "compute")
                .body(body("123456789-compute@developer.gserviceaccount.com"))
                .when().post(SERVICES)
                .then().statusCode(200);
    }
}

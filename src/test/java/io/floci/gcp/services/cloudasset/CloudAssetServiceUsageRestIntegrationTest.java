package io.floci.gcp.services.cloudasset;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(CloudAssetServiceUsageRestIntegrationTest.EnforceProfile.class)
class CloudAssetServiceUsageRestIntegrationTest {

    @Test
    void disabledApiAnswersServiceDisabledUntilEnabled() {
        String project = "cai-su-" + UUID.randomUUID().toString().substring(0, 8);
        String search = "/cloudasset/v1/projects/" + project + ":searchAllResources";
        given().urlEncodingEnabled(false).get(search).then().statusCode(403)
                .body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.details[0].reason", equalTo("SERVICE_DISABLED"))
                .body("error.details[0].metadata.service", equalTo("cloudasset.googleapis.com"))
                .body("error.details[0].metadata.activationUrl", equalTo(
                        "https://console.developers.google.com/apis/api/cloudasset.googleapis.com/overview?project=" + project));

        given().urlEncodingEnabled(false).contentType("application/json").body("{}")
                .post("/v1/projects/" + project + "/services/cloudasset.googleapis.com:enable").then().statusCode(200);
        given().urlEncodingEnabled(false).get(search).then().statusCode(200);
    }

    public static class EnforceProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.serviceusage.enforcement-enabled", "true");
        }
    }
}

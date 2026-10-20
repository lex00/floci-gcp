package io.floci.gcp.services.cloudasset;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(CloudAssetLagRestIntegrationTest.LagProfile.class)
class CloudAssetLagRestIntegrationTest {

    private static List<String> names(String project) {
        List<String> names = given().urlEncodingEnabled(false).get("/cloudasset/v1/projects/" + project + ":searchAllResources")
                .then().statusCode(200).extract().jsonPath().getList("results.name");
        return names == null ? List.of() : names;
    }

    @Test
    void newAndDeletedResourcesKeepTheirPreviousAnswerForTheLag() throws Exception {
        String project = "cai-lag-" + UUID.randomUUID().toString().substring(0, 8);
        String topic = "//pubsub.googleapis.com/projects/" + project + "/topics/events";
        given().contentType("application/json").body(Map.of()).put("/v1/projects/" + project + "/topics/events")
                .then().statusCode(200);
        assertEquals(List.of(), names(project));
        Thread.sleep(1200);
        assertEquals(List.of(topic), names(project));

        given().delete("/v1/projects/" + project + "/topics/events").then().statusCode(200);
        assertEquals(List.of(topic), names(project));
        Thread.sleep(1200);
        assertEquals(List.of(), names(project));
    }

    public static class LagProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.cloudasset.lag-seconds", "1");
        }
    }
}

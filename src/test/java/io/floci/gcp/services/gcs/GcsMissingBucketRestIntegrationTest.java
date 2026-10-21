package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
class GcsMissingBucketRestIntegrationTest {

    private static final String MISSING = "gcs-missing-bucket-it-absent";
    private static final String BUCKET_MISSING = "The specified bucket does not exist.";

    @Test
    void objectRequestsInAMissingBucketSayTheBucketDoesNotExist() {
        given().when().get("/storage/v1/b/" + MISSING + "/o/rec?alt=media")
                .then().statusCode(404).body("error.message", equalTo(BUCKET_MISSING));
        given().when().get("/storage/v1/b/" + MISSING + "/o/rec")
                .then().statusCode(404).body("error.message", equalTo(BUCKET_MISSING));
        given().queryParam("ifGenerationMatch", 1).when().delete("/storage/v1/b/" + MISSING + "/o/rec")
                .then().statusCode(404).body("error.message", equalTo(BUCKET_MISSING));
        given().contentType("application/octet-stream").body("x".getBytes(StandardCharsets.UTF_8))
                .queryParam("uploadType", "media").queryParam("name", "rec").queryParam("ifGenerationMatch", 0)
                .when().post("/upload/storage/v1/b/" + MISSING + "/o")
                .then().statusCode(404).body("error.message", equalTo(BUCKET_MISSING));
        given().when().get("/storage/v1/b/" + MISSING)
                .then().statusCode(404).body("error.message", equalTo(BUCKET_MISSING));
    }

    @Test
    void aMissingObjectInALiveBucketStillSaysTheObjectIsMissing() {
        String bucket = "gcs-missing-bucket-it-live";
        given().contentType("application/json").body(Map.of("name", bucket))
                .when().post("/storage/v1/b?project=test-project")
                .then().statusCode(200);
        given().when().get("/storage/v1/b/" + bucket + "/o/rec?alt=media")
                .then().statusCode(404).body("error.message", equalTo("Object not found: rec"));
        given().when().delete("/storage/v1/b/" + bucket + "/o/rec")
                .then().statusCode(404).body("error.message", equalTo("Object not found: rec"));
    }
}

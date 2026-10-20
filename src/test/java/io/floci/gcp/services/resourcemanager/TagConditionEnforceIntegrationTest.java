package io.floci.gcp.services.resourcemanager;

import io.floci.gcp.services.credentials.CredentialTokenService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

@QuarkusTest
@TestProfile(TagConditionEnforceIntegrationTest.EnforceProfile.class)
class TagConditionEnforceIntegrationTest {

    @Inject
    CredentialTokenService tokens;

    private String project;
    private String number;
    private String member;
    private String credential;
    private String fenceKey;
    private String estateA;
    private String estateB;

    private static Response post(String path, Map<String, Object> body) {
        return given().urlEncodingEnabled(false).contentType("application/json").body(body).post(path);
    }

    @BeforeEach
    void setup() throws Exception {
        project = "tagcond-" + UUID.randomUUID().toString().substring(0, 8);
        number = given().get("/v1/projects/" + project).then().statusCode(200).extract().path("projectNumber");
        String email = "fence@" + project + ".iam.gserviceaccount.com";
        member = "serviceAccount:" + email;
        credential = "Bearer " + tokens.mintImpersonatedToken(email, Instant.now().plusSeconds(300)).getTokenValue();
        fenceKey = post("/v3/tagKeys", Map.of("parent", "projects/" + project, "shortName", "fence"))
                .then().statusCode(200).extract().path("response.name");
        estateA = post("/v3/tagValues", Map.of("parent", fenceKey, "shortName", "estate-a"))
                .then().statusCode(200).extract().path("response.name");
        estateB = post("/v3/tagValues", Map.of("parent", fenceKey, "shortName", "estate-b"))
                .then().statusCode(200).extract().path("response.name");
        for (String disk : List.of("inside", "outside")) {
            post("/compute/v1/projects/" + project + "/zones/us-central1-a/disks", Map.of("name", disk, "sizeGb", "10"))
                    .then().statusCode(200);
        }
        Thread.sleep(150);
    }

    private void grantViewerWhere(String expression) {
        Map<String, Object> binding = Map.of("role", "roles/compute.viewer", "members", List.of(member),
                "condition", Map.of("title", "fence", "expression", expression));
        post("/v1/projects/" + project + ":setIamPolicy", Map.of("policy", Map.of("version", 3, "bindings", List.of(binding))))
                .then().statusCode(200);
    }

    private void bind(String parent, String value) {
        post("/v3/tagBindings", Map.of("parent", parent, "tagValue", value)).then().statusCode(200);
    }

    private String disk(String name) {
        String id = given().get("/compute/v1/projects/" + project + "/zones/us-central1-a/disks/" + name)
                .then().statusCode(200).extract().path("id");
        return "//compute.googleapis.com/projects/" + number + "/zones/us-central1-a/disks/" + id;
    }

    private int read(String name) {
        return given().header("Authorization", credential)
                .get("/compute/v1/projects/" + project + "/zones/us-central1-a/disks/" + name).statusCode();
    }

    @Test
    void matchTagGrantsOnlyWhereTheValueIsBound() {
        grantViewerWhere("resource.matchTag('" + project + "/fence', 'estate-a')");
        bind(disk("inside"), estateA);
        assertEquals(200, read("inside"));
        assertEquals(403, read("outside"));
    }

    @Test
    void projectBindingIsInheritedAndADirectValueOverridesIt() {
        grantViewerWhere("resource.matchTag('" + number + "/fence', 'estate-a')");
        bind("//cloudresourcemanager.googleapis.com/projects/" + number, estateA);
        assertEquals(200, read("outside"));
        bind(disk("outside"), estateB);
        assertEquals(403, read("outside"));
        assertEquals(200, read("inside"));
    }

    @Test
    void matchTagIdMatchesKeyAndValueIds() {
        grantViewerWhere("resource.matchTagId('" + fenceKey + "', '" + estateA + "')");
        bind(disk("inside"), estateA);
        assertEquals(200, read("inside"));
        assertEquals(403, read("outside"));
    }

    @Test
    void policyVersionAndConditionTitleRoundTripAndUnsupportedConditionsFailLoudly() {
        grantViewerWhere("resource.matchTag('" + project + "/fence', 'estate-a')");
        post("/v1/projects/" + project + ":getIamPolicy", Map.of("options", Map.of("requestedPolicyVersion", 3)))
                .then().statusCode(200).body("version", equalTo(3))
                .body("bindings[0].condition.title", equalTo("fence"));
        Map<String, Object> binding = Map.of("role", "roles/compute.viewer", "members", List.of(member),
                "condition", Map.of("title", "bad", "expression", "resource.hasTag('" + project + "/fence')"));
        post("/v1/projects/" + project + ":setIamPolicy", Map.of("policy", Map.of("version", 3, "bindings", List.of(binding))))
                .then().statusCode(200);
        given().header("Authorization", credential).get("/compute/v1/projects/" + project + "/zones/us-central1-a/disks/inside")
                .then().statusCode(400).body("error.message", containsString("Unsupported IAM condition"));
    }

    public static class EnforceProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.iam.authorization-mode", "enforce");
        }
    }
}

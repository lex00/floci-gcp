package io.floci.gcp.services.cloudasset;

import io.floci.gcp.services.credentials.CredentialTokenService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

@QuarkusTest
@TestProfile(CloudAssetIamEnforcementRestIntegrationTest.EnforceProfile.class)
class CloudAssetIamEnforcementRestIntegrationTest {

    @Inject
    CredentialTokenService tokens;

    private String project;
    private String member;
    private String credential;

    @BeforeEach
    void setup() {
        project = "cai-iam-" + UUID.randomUUID().toString().substring(0, 8);
        String email = "sweeper@" + project + ".iam.gserviceaccount.com";
        member = "serviceAccount:" + email;
        credential = "Bearer " + tokens.mintImpersonatedToken(email, Instant.now().plusSeconds(300)).getTokenValue();
    }

    private void grant(String... roles) {
        List<Map<String, Object>> bindings = new ArrayList<>();
        for (String role : roles) {
            bindings.add(Map.of("role", role, "members", List.of(member)));
        }
        given().urlEncodingEnabled(false).contentType("application/json").body(Map.of("policy", Map.of("bindings", bindings)))
                .post("/v1/projects/" + project + ":setIamPolicy").then().statusCode(200);
    }

    private io.restassured.response.ValidatableResponse search(String scope) {
        return given().urlEncodingEnabled(false).header("Authorization", credential)
                .get("/cloudasset/v1/" + scope + ":searchAllResources").then();
    }

    @Test
    void callerWithoutAGrantIsDeniedTheSearchPermission() {
        search("projects/" + project).statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"))
                .body("error.message", containsString("cloudasset.assets.searchAllResources"));
    }

    @Test
    void cloudAssetViewerAloneLacksServiceUsageUse() {
        grant("roles/cloudasset.viewer");
        search("projects/" + project).statusCode(403).body("error.message", containsString("serviceusage.services.use"));
        grant("roles/cloudasset.viewer", "roles/serviceusage.serviceUsageConsumer");
        search("projects/" + project).statusCode(200);
    }

    @Test
    void basicRolesAndProjectNumbersResolveToTheProjectPolicy() {
        grant("roles/viewer");
        search("projects/" + project).statusCode(403).body("error.message", containsString("serviceusage.services.use"));
        grant("roles/editor");
        search("projects/" + project).statusCode(200);
        String number = given().get("/v1/projects/" + project).then().extract().path("projectNumber");
        search("projects/" + number).statusCode(200);
    }

    @Test
    void grantInAnotherProjectDoesNotReachThisOne() {
        String other = "cai-iam-other-" + UUID.randomUUID().toString().substring(0, 4);
        given().urlEncodingEnabled(false).contentType("application/json").body(Map.of("policy", Map.of("bindings",
                List.of(Map.of("role", "roles/owner", "members", List.of(member))))))
                .post("/v1/projects/" + other + ":setIamPolicy").then().statusCode(200);
        search("projects/" + project).statusCode(403);
        search("projects/" + other).statusCode(200);
    }

    public static class EnforceProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("floci-gcp.services.iam.authorization-mode", "enforce");
        }
    }
}

package io.floci.gcp.services.compute;

import io.floci.gcp.services.credentials.CredentialTokenService;
import io.floci.gcp.services.iam.IamService;
import io.floci.gcp.services.iam.model.StoredPolicy;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;

abstract class ComputeIamEnforcementContract {
    @Inject
    IamService policies;
    @Inject
    CredentialTokenService tokens;

    String project;
    String root;
    String member;
    String credential;

    abstract boolean enforced();

    @BeforeEach
    void setup() {
        project = "compute-iam-" + UUID.randomUUID().toString().substring(0, 8);
        root = "/compute/v1/projects/" + project;
        String email = "builder@" + project + ".iam.gserviceaccount.com";
        member = "serviceAccount:" + email;
        credential = "Bearer " + tokens.mintImpersonatedToken(email, Instant.now().plusSeconds(300)).getTokenValue();
    }

    private RequestSpecification caller() {
        return given().header("Authorization", credential).contentType("application/json");
    }

    private Response callerPost(String path, Map<String, Object> body) {
        return caller().body(body).post(root + path);
    }

    private void anonymous(String path, Map<String, Object> body) throws Exception {
        done(given().contentType("application/json").body(body).post(root + path));
    }

    private static void done(Response response) throws Exception {
        response.then().statusCode(200);
        String link = response.jsonPath().getString("selfLink");
        assertNotNull(link);
        String operation = link.substring(link.indexOf("/compute/v1/"));
        for (int i = 0; i < 100; i++) {
            if ("DONE".equals(given().get(operation).then().statusCode(200).extract().path("status"))) {
                return;
            }
            Thread.sleep(10);
        }
        fail("Compute operation did not finish");
    }

    private void expectDenied(Response response) throws Exception {
        if (enforced()) {
            response.then().statusCode(403).body("error.status", equalTo("PERMISSION_DENIED"));
        } else {
            done(response);
        }
    }

    private int status(String path) {
        return given().get(root + path).statusCode();
    }

    private void grant(String resource, String... roles) {
        grantWithCondition(resource, null, roles);
    }

    private void grantWithCondition(String resource, String expression, String... roles) {
        StoredPolicy policy = new StoredPolicy();
        List<Map<String, Object>> bindings = new ArrayList<>();
        for (String role : roles) {
            if (expression == null) {
                bindings.add(Map.of("role", role, "members", List.of(member)));
            } else {
                bindings.add(Map.of("role", role, "members", List.of(member),
                        "condition", Map.of("title", "scope", "expression", expression)));
            }
        }
        if (expression != null) {
            policy.setVersion(3);
        }
        policy.setBindings(bindings);
        policies.setPolicy(resource, policy);
    }

    private void network(String name) throws Exception {
        anonymous("/global/networks", Map.of("name", name, "autoCreateSubnetworks", false));
    }

    @Test
    void networkViewerReadsButCannotCreateOrDelete() throws Exception {
        grant("projects/" + project, "roles/compute.networkViewer");
        expectDenied(callerPost("/global/networks", Map.of("name", "denied", "autoCreateSubnetworks", false)));
        assertEquals(enforced() ? 404 : 200, status("/global/networks/denied"));

        network("shared");
        caller().get(root + "/global/networks/shared").then().statusCode(200);
        caller().get(root + "/global/networks").then().statusCode(200);
        expectDenied(caller().delete(root + "/global/networks/shared"));
        assertEquals(enforced() ? 200 : 404, status("/global/networks/shared"));
    }

    @Test
    void conditionLimitsCreationToTheNamedNetwork() throws Exception {
        grantWithCondition("projects/" + project,
                "resource.name == 'projects/" + project + "/global/networks/allowed'", "roles/compute.networkAdmin");
        done(callerPost("/global/networks", Map.of("name", "allowed", "autoCreateSubnetworks", false)));
        expectDenied(callerPost("/global/networks", Map.of("name", "excluded", "autoCreateSubnetworks", false)));
        assertEquals(enforced() ? 404 : 200, status("/global/networks/excluded"));
    }

    @Test
    void subnetworkCreationNeedsUpdatePolicyOnTheReferencedNetwork() throws Exception {
        network("net");
        grantWithCondition("projects/" + project,
                "resource.name != 'projects/" + project + "/global/networks/net'", "roles/compute.networkAdmin");
        expectDenied(callerPost("/regions/us-central1/subnetworks", Map.of("name", "sub",
                "network", "global/networks/net", "ipCidrRange", "10.30.0.0/24")));
        assertEquals(enforced() ? 404 : 200, status("/regions/us-central1/subnetworks/sub"));
    }

    @Test
    void grantsDoNotCrossProjects() throws Exception {
        grant("projects/" + project, "roles/compute.networkAdmin");
        String other = "/compute/v1/projects/" + project + "-other";
        Response response = caller().body(Map.of("name", "net", "autoCreateSubnetworks", false))
                .post(other + "/global/networks");
        expectDenied(response);
    }

    @Test
    void instanceAdminBuildsALabelledInstanceFromABlankDisk() throws Exception {
        network("net");
        anonymous("/regions/us-central1/subnetworks", Map.of("name", "sub", "network", "global/networks/net",
                "ipCidrRange", "10.31.0.0/24"));
        Map<String, Object> instance = Map.of("name", "vm", "machineType", "zones/us-central1-a/machineTypes/e2-small",
                "labels", Map.of("env", "compat"),
                "networkInterfaces", List.of(Map.of("subnetwork", "regions/us-central1/subnetworks/sub")),
                "disks", List.of(Map.of("boot", true, "source", "zones/us-central1-a/disks/boot")));

        grant("projects/" + project, "roles/compute.networkAdmin");
        expectDenied(callerPost("/zones/us-central1-a/disks", Map.of("name", "boot", "sizeGb", "10")));
        if (enforced()) {
            grant("projects/" + project, "roles/compute.instanceAdmin.v1");
            done(callerPost("/zones/us-central1-a/disks", Map.of("name", "boot", "sizeGb", "10")));
        }
        done(callerPost("/zones/us-central1-a/instances", instance));
        caller().get(root + "/zones/us-central1-a/instances/vm").then().statusCode(200)
                .body("labels.env", equalTo("compat"));
        done(caller().body(Map.of()).post(root + "/zones/us-central1-a/instances/vm/stop"));
    }

    @Test
    void uncataloguedRoleNeitherGrantsNorBlocksAKnownGrant() throws Exception {
        network("net");
        grant("projects/" + project, "roles/compute.notARealRole");
        expectDeniedGet("/global/networks/net");
        grant("projects/" + project, "roles/compute.notARealRole", "roles/compute.networkViewer");
        caller().get(root + "/global/networks/net").then().statusCode(200);
    }

    private void expectDeniedGet(String path) {
        caller().get(root + path).then().statusCode(enforced() ? 403 : 200);
    }

    @Test
    void unmappedOperationsAreRefusedForEvaluatedCallersOnly() throws Exception {
        grant("projects/" + project, "roles/compute.admin");
        Response image = caller().get(root + "/global/images");
        if (enforced()) {
            image.then().statusCode(400).body("error.status", equalTo("FAILED_PRECONDITION"));
        } else {
            image.then().statusCode(200);
        }
        network("anonymous");
        given().get(root + "/global/networks/anonymous").then().statusCode(200);
        given().header("Authorization", "Bearer not-a-floci-token").get(root + "/global/networks/anonymous")
                .then().statusCode(200);
    }
}

package io.floci.gcp.services.resourcemanager;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class ResourceManagerTagsRestIntegrationTest {

    private static Response post(String path, Map<String, Object> body) {
        return given().contentType("application/json").body(body).post(path);
    }

    private static String project() {
        return "tags-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String projectNumber(String project) {
        return given().get("/v1/projects/" + project).then().statusCode(200).extract().path("projectNumber");
    }

    private static JsonPath created(Response operation) {
        return operation.then().statusCode(200).body("done", equalTo(true)).extract().jsonPath();
    }

    @Test
    void keysAndValuesUseTheProjectIdNamespace() {
        String project = project(), number = projectNumber(project);
        JsonPath key = created(post("/v3/tagKeys", Map.of("parent", "projects/" + project, "shortName", "env",
                "description", "environment")));
        assertEquals("type.googleapis.com/google.cloud.resourcemanager.v3.TagKey", key.getString("response.'@type'"));
        String keyName = key.getString("response.name");
        assertTrue(keyName.matches("tagKeys/[0-9]+"));
        assertEquals(project + "/env", key.getString("response.namespacedName"));

        given().get("/v3/" + keyName).then().statusCode(200).body("shortName", equalTo("env"));
        given().get("/v3/tagKeys/namespaced?name=" + project + "/env").then().statusCode(200).body("name", equalTo(keyName));
        given().get("/v3/tagKeys/namespaced?name=" + number + "/env").then().statusCode(200).body("name", equalTo(keyName));
        given().get("/v3/tagKeys?parent=projects/" + number).then().statusCode(200).body("tagKeys.name", equalTo(List.of(keyName)));
        post("/v3/tagKeys", Map.of("parent", "projects/" + project, "shortName", "env")).then().statusCode(409)
                .body("error.status", equalTo("ALREADY_EXISTS"));
        post("/v3/tagKeys", Map.of("parent", "projects/" + project, "shortName", "-bad")).then().statusCode(400);

        JsonPath value = created(post("/v3/tagValues", Map.of("parent", keyName, "shortName", "prod")));
        String valueName = value.getString("response.name");
        assertEquals(project + "/env/prod", value.getString("response.namespacedName"));
        given().get("/v3/tagValues?parent=" + keyName).then().statusCode(200).body("tagValues.name", equalTo(List.of(valueName)));
        given().get("/v3/tagValues/namespaced?name=" + project + "/env/prod").then().statusCode(200)
                .body("name", equalTo(valueName));
        given().get("/v3/tagValues?parent=" + project + "/env").then().statusCode(200)
                .body("tagValues.name", equalTo(List.of(valueName)));

        String operation = value.getString("name");
        given().get("/v3/" + operation).then().statusCode(200).body("response.name", equalTo(valueName));
    }

    @Test
    void descriptionPatchesWithTheCurrentEtagOnly() {
        String project = project();
        JsonPath key = created(post("/v3/tagKeys", Map.of("parent", "projects/" + project, "shortName", "team")));
        String keyName = key.getString("response.name"), etag = key.getString("response.etag");
        given().contentType("application/json").body(Map.of("description", "owning team", "etag", etag))
                .patch("/v3/" + keyName + "?updateMask=description").then().statusCode(200)
                .body("response.description", equalTo("owning team"));
        given().contentType("application/json").body(Map.of("description", "stale", "etag", etag))
                .patch("/v3/" + keyName + "?updateMask=description").then().statusCode(409)
                .body("error.status", equalTo("ABORTED"));
        given().contentType("application/json").body(Map.of("shortName", "renamed"))
                .patch("/v3/" + keyName + "?updateMask=shortName").then().statusCode(400);
    }

    @Test
    void bindingsBlockDeletionUntilRemoved() {
        String project = project(), number = projectNumber(project);
        String keyName = created(post("/v3/tagKeys", Map.of("parent", "projects/" + project, "shortName", "fence")))
                .getString("response.name");
        String valueName = created(post("/v3/tagValues", Map.of("parent", keyName, "shortName", "estate-a")))
                .getString("response.name");
        String parent = "//cloudresourcemanager.googleapis.com/projects/" + number;
        JsonPath binding = created(post("/v3/tagBindings", Map.of("parent", parent, "tagValue", valueName)));
        String bindingName = binding.getString("response.name");
        assertEquals("tagBindings/" + URLEncoder.encode(parent, StandardCharsets.UTF_8) + "/" + valueName, bindingName);
        assertEquals(project + "/fence/estate-a", binding.getString("response.tagValueNamespacedName"));

        given().queryParam("parent", parent).get("/v3/tagBindings").then().statusCode(200)
                .body("tagBindings.name", equalTo(List.of(bindingName)));
        given().queryParam("parent", "//cloudresourcemanager.googleapis.com/projects/" + project).get("/v3/tagBindings")
                .then().statusCode(200).body("tagBindings.name", equalTo(List.of(bindingName)));
        post("/v3/tagBindings", Map.of("parent", parent, "tagValue", valueName)).then().statusCode(409);

        given().delete("/v3/" + valueName).then().statusCode(400).body("error.status", equalTo("FAILED_PRECONDITION"));
        given().delete("/v3/" + keyName).then().statusCode(400).body("error.status", equalTo("FAILED_PRECONDITION"));

        given().urlEncodingEnabled(false).delete("/v3/" + bindingName).then().statusCode(200)
                .body("response.'@type'", equalTo("type.googleapis.com/google.protobuf.Empty"));
        given().queryParam("parent", parent).get("/v3/tagBindings").then().statusCode(200).body("tagBindings", equalTo(null));
        given().delete("/v3/" + valueName).then().statusCode(200);
        given().delete("/v3/" + keyName).then().statusCode(200);
        given().get("/v3/" + keyName).then().statusCode(403);
    }

    @Test
    void bindingsReachBucketsAndComputeInstancesThatExist() {
        String project = project(), number = projectNumber(project);
        String keyName = created(post("/v3/tagKeys", Map.of("parent", "projects/" + project, "shortName", "tier")))
                .getString("response.name");
        created(post("/v3/tagValues", Map.of("parent", keyName, "shortName", "gold")));

        String bucket = project + "-tagged";
        post("/storage/v1/b?project=" + project, Map.of("name", bucket)).then().statusCode(200);
        String bucketBinding = created(post("/v3/tagBindings", Map.of("parent",
                "//storage.googleapis.com/projects/_/buckets/" + bucket, "tagValueNamespacedName", project + "/tier/gold")))
                .getString("response.name");
        given().urlEncodingEnabled(false).delete("/v3/" + bucketBinding.replace("%", "%25")).then().statusCode(200);
        created(post("/v3/tagBindings", Map.of("parent", "//storage.googleapis.com/projects/_/buckets/" + bucket,
                "tagValueNamespacedName", project + "/tier/gold")));
        post("/v3/tagBindings", Map.of("parent", "//storage.googleapis.com/projects/_/buckets/" + bucket + "-missing",
                "tagValueNamespacedName", project + "/tier/gold")).then().statusCode(404);

        String compute = "/compute/v1/projects/" + project;
        post(compute + "/zones/us-central1-a/disks", Map.of("name", "data", "sizeGb", "10")).then().statusCode(200);
        String diskId = given().get(compute + "/zones/us-central1-a/disks/data").then().statusCode(200).extract().path("id");
        created(post("/v3/tagBindings", Map.of("parent", "//compute.googleapis.com/projects/" + number
                + "/zones/us-central1-a/disks/" + diskId, "tagValueNamespacedName", project + "/tier/gold")));
        post("/v3/tagBindings", Map.of("parent", "//compute.googleapis.com/projects/" + number
                + "/zones/us-central1-a/disks/999", "tagValueNamespacedName", project + "/tier/gold")).then().statusCode(404);

        post("/v3/tagBindings", Map.of("parent", "//sqladmin.googleapis.com/projects/" + project + "/instances/db",
                "tagValueNamespacedName", project + "/tier/gold")).then().statusCode(501)
                .body("error.message", startsWith("Tag bindings on //sqladmin.googleapis.com"));
    }
}

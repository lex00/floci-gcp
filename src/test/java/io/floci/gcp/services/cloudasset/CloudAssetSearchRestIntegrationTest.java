package io.floci.gcp.services.cloudasset;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudAssetSearchRestIntegrationTest {

    private String project;
    private String number;

    private static Response post(String path, Map<String, Object> body) {
        return given().contentType("application/json").body(body).post(path);
    }

    private String url(String scope, String... params) {
        StringBuilder url = new StringBuilder("/cloudasset/v1/" + scope + ":searchAllResources");
        for (int i = 0; i < params.length; i += 2) {
            url.append(i == 0 ? '?' : '&').append(params[i]).append('=')
                    .append(URLEncoder.encode(params[i + 1], StandardCharsets.UTF_8));
        }
        return url.toString();
    }

    private Response get(String... params) {
        return given().urlEncodingEnabled(false).get(url("projects/" + project, params));
    }

    private JsonPath search(String query) {
        Response response = query == null ? get() : get("query", query);
        return response.then().statusCode(200).extract().jsonPath();
    }

    @BeforeEach
    void seed() throws Exception {
        project = "cai-" + UUID.randomUUID().toString().substring(0, 8);
        number = given().get("/v1/projects/" + project).then().statusCode(200).extract().path("projectNumber");
        post("/storage/v1/b?project=" + project, Map.of("name", project + "-estate",
                "labels", Map.of("tofu-estate", "prod", "tofu-address", "a1"))).then().statusCode(200);
        given().contentType("application/json").body(Map.of("labels", Map.of("tofu-estate", "prod")))
                .put("/v1/projects/" + project + "/topics/events").then().statusCode(200);
        given().contentType("application/json").body(Map.of("topic", "projects/" + project + "/topics/events"))
                .put("/v1/projects/" + project + "/subscriptions/events-sub").then().statusCode(200);
        String compute = "/compute/v1/projects/" + project;
        post(compute + "/global/networks", Map.of("name", "net", "autoCreateSubnetworks", false)).then().statusCode(200);
        post(compute + "/zones/us-central1-a/disks", Map.of("name", "data", "sizeGb", "10",
                "labels", Map.of("tofu-estate", "dev"))).then().statusCode(200);
        post("/v1/projects/" + project + "/serviceAccounts", Map.of("accountId", "fence-sa")).then().statusCode(200);
        Thread.sleep(150);
    }

    @Test
    void resultsUseAssetNamesTypesAndTheProjectNumber() {
        JsonPath all = search(null);
        List<String> names = all.getList("results.name");
        assertTrue(names.contains("//storage.googleapis.com/" + project + "-estate"));
        assertTrue(names.contains("//pubsub.googleapis.com/projects/" + project + "/topics/events"));
        assertTrue(names.contains("//pubsub.googleapis.com/projects/" + project + "/subscriptions/events-sub"));
        assertTrue(names.contains("//compute.googleapis.com/projects/" + project + "/global/networks/net"));
        assertTrue(names.contains("//compute.googleapis.com/projects/" + project + "/zones/us-central1-a/disks/data"));
        assertTrue(names.stream().anyMatch(n -> n.startsWith("//iam.googleapis.com/projects/" + project + "/serviceAccounts/")));
        assertEquals(Set.of("projects/" + number), Set.copyOf(all.getList("results.project")));
        String bucket = "results.find { it.name == '//storage.googleapis.com/" + project + "-estate' }";
        assertEquals("storage.googleapis.com/Bucket", all.getString(bucket + ".assetType"));
        assertEquals("us", all.getString(bucket + ".location"));
        assertEquals("us-central1-a", all.getString("results.find { it.assetType == 'compute.googleapis.com/Disk' }.location"));
        assertEquals("global", all.getString("results.find { it.assetType == 'compute.googleapis.com/Network' }.location"));
    }

    @Test
    void labelStatementsJoinWithAnd() {
        assertEquals(Set.of("//storage.googleapis.com/" + project + "-estate", "//pubsub.googleapis.com/projects/" + project + "/topics/events"),
                Set.copyOf(search("labels.tofu-estate=prod").getList("results.name")));
        assertEquals(List.of("//storage.googleapis.com/" + project + "-estate"),
                search("labels.tofu-estate=prod AND labels.tofu-address=a1").getList("results.name"));
        assertEquals(3, search("labels.tofu-estate:*").getList("results").size());
        get("query", "name:events").then().statusCode(400).body("error.status", equalTo("INVALID_ARGUMENT"));
    }

    @Test
    void tagStatementsMatchBindingsOnBucketsAndComputeById() {
        String key = post("/v3/tagKeys", Map.of("parent", "projects/" + project, "shortName", "fence"))
                .then().statusCode(200).extract().path("response.name");
        String value = post("/v3/tagValues", Map.of("parent", key, "shortName", "estate-a"))
                .then().statusCode(200).extract().path("response.name");
        post("/v3/tagBindings", Map.of("parent", "//storage.googleapis.com/projects/_/buckets/" + project + "-estate",
                "tagValue", value)).then().statusCode(200);
        String diskId = given().get("/compute/v1/projects/" + project + "/zones/us-central1-a/disks/data").then()
                .statusCode(200).extract().path("id");
        post("/v3/tagBindings", Map.of("parent", "//compute.googleapis.com/projects/" + number
                + "/zones/us-central1-a/disks/" + diskId, "tagValue", value)).then().statusCode(200);

        Set<String> tagged = Set.of("//storage.googleapis.com/" + project + "-estate",
                "//compute.googleapis.com/projects/" + project + "/zones/us-central1-a/disks/data");
        assertEquals(tagged, Set.copyOf(search("tagValues:\"" + project + "/fence/estate-a\"").getList("results.name")));
        assertEquals(tagged, Set.copyOf(search("tagValues:fence/estate-a").getList("results.name")));
        assertEquals(tagged, Set.copyOf(search("tagKeys=\"" + project + "/fence\"").getList("results.name")));
        assertEquals(tagged, Set.copyOf(search("tagValueIds=" + value).getList("results.name")));
        JsonPath bucket = search("tagValueIds=" + value + " AND labels.tofu-estate=prod");
        assertEquals(List.of(project + "/fence/estate-a"), bucket.getList("results[0].tagValues"));
        assertEquals(value, bucket.getString("results[0].tags[0].tagValueId"));
    }

    @Test
    void readMaskPagingAndEmptyResults() {
        JsonPath masked = get("readMask", "name,assetType,project,location,labels,createTime,updateTime",
                "query", "labels.tofu-address=a1").then().statusCode(200).extract().jsonPath();
        assertEquals(Set.of("name", "assetType", "project", "location", "labels", "createTime", "updateTime"),
                masked.getMap("results[0]").keySet());

        List<String> seen = new ArrayList<>();
        String token = null, used = null;
        do {
            Response response = token == null ? get("pageSize", "2") : get("pageSize", "2", "pageToken", token);
            used = token == null ? used : token;
            JsonPath page = response.then().statusCode(200).extract().jsonPath();
            seen.addAll(page.getList("results.name"));
            token = page.getString("nextPageToken");
        } while (token != null);
        assertEquals(search(null).getList("results.name"), seen);
        get("pageSize", "2", "pageToken", used).then().statusCode(400)
                .body("error.status", equalTo("INVALID_ARGUMENT"));
        get("pageToken", "unknown").then().statusCode(400);

        String empty = get("query", "labels.tofu-estate=none").then().statusCode(200).extract().asString();
        assertEquals("{}", empty);
    }

    @Test
    void assetTypesOrderByAndHostRouting() {
        List<String> types = get("assetTypes", "compute.googleapis.com.*").then().statusCode(200)
                .extract().jsonPath().getList("results.assetType");
        assertFalse(types.isEmpty());
        assertTrue(types.stream().allMatch(t -> t.startsWith("compute.googleapis.com/")));

        List<String> descending = get("orderBy", "name DESC").then().statusCode(200)
                .extract().jsonPath().getList("results.name");
        List<String> sorted = new ArrayList<>(descending);
        sorted.sort(Comparator.reverseOrder());
        assertEquals(sorted, descending);

        given().urlEncodingEnabled(false).header("Host", "cloudasset.googleapis.com")
                .get(url("projects/" + number, "query", "labels.tofu-address=a1").substring("/cloudasset".length()))
                .then().statusCode(200)
                .body("results[0].name", equalTo("//storage.googleapis.com/" + project + "-estate"));
        given().urlEncodingEnabled(false).get(url("folders/123")).then().statusCode(501)
                .body("error.message", containsString("folder"));
        assertNull(search("labels.tofu-estate=prod").getString("nextPageToken"));
    }
}

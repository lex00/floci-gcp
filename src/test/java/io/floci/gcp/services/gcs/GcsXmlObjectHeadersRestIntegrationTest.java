package io.floci.gcp.services.gcs;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class GcsXmlObjectHeadersRestIntegrationTest {

    private static final String BUCKET = "xml-object-headers-bucket";

    @BeforeEach
    void createObject() {
        given().contentType("application/json").body(Map.of("name", BUCKET))
                .post("/storage/v1/b?project=test-project");
        given().contentType("application/pdf").body("%PDF".getBytes(StandardCharsets.US_ASCII))
                .post("/upload/storage/v1/b/" + BUCKET + "/o?uploadType=media&name=report.pdf")
                .then().statusCode(200);
        given().contentType("application/json")
                .body(Map.of("contentDisposition", "inline", "contentLanguage", "en", "cacheControl", "no-cache"))
                .patch("/storage/v1/b/" + BUCKET + "/o/report.pdf")
                .then().statusCode(200);
    }

    @Test
    void downloadReturnsStoredPresentationHeaders() {
        given().get("/" + BUCKET + "/report.pdf")
                .then().statusCode(200)
                .header("Content-Type", startsWith("application/pdf"))
                .header("Content-Disposition", equalTo("inline"))
                .header("Content-Language", equalTo("en"))
                .header("Cache-Control", equalTo("no-cache"));
    }

    @Test
    void responseQueryParametersOverrideDispositionAndType() {
        given().urlEncodingEnabled(false)
                .get("/" + BUCKET + "/report.pdf?X-Goog-Algorithm=GOOG4-RSA-SHA256&X-Goog-Expires=900"
                        + "&response-content-disposition=attachment%3B%20filename%3D%22report.pdf%22"
                        + "&response-content-type=application%2Foctet-stream")
                .then().statusCode(200)
                .header("Content-Disposition", equalTo("attachment; filename=\"report.pdf\""))
                .header("Content-Type", startsWith("application/octet-stream"))
                .header("Cache-Control", equalTo("no-cache"));
    }
}

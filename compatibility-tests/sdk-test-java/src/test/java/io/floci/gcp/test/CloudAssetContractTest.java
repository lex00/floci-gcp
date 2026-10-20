package io.floci.gcp.test;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.cloud.NoCredentials;
import com.google.cloud.asset.v1.AssetServiceClient;
import com.google.cloud.asset.v1.AssetServiceSettings;
import com.google.cloud.asset.v1.ResourceSearchResult;
import com.google.cloud.asset.v1.SearchAllResourcesRequest;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.*;

class CloudAssetContractTest {
    @Test void searchFindsALabelledBucketThroughThePathPrefix() throws Exception {
        String project = TestFixtures.uniqueName("java-cai");
        String bucket = project + "-estate";
        Storage storage = StorageOptions.newBuilder().setHost(TestFixtures.endpoint()).setProjectId(project)
                .setCredentials(NoCredentials.getInstance()).build().getService();
        storage.create(BucketInfo.newBuilder(bucket).setLabels(Map.of("tofu-estate", "prod")).build());
        try (AssetServiceClient assets = AssetServiceClient.create(AssetServiceSettings.newHttpJsonBuilder()
                .setEndpoint(TestFixtures.endpoint() + "/cloudasset").setCredentialsProvider(NoCredentialsProvider.create()).build())) {
            SearchAllResourcesRequest request = SearchAllResourcesRequest.newBuilder().setScope("projects/" + project)
                    .setQuery("labels.tofu-estate=prod").build();
            List<ResourceSearchResult> results = StreamSupport.stream(assets.searchAllResources(request).iterateAll().spliterator(), false).toList();
            assertThat(results).extracting(ResourceSearchResult::getName).containsExactly("//storage.googleapis.com/" + bucket);
            assertThat(results.get(0).getAssetType()).isEqualTo("storage.googleapis.com/Bucket");
            assertThat(results.get(0).getProject()).startsWith("projects/");
        } finally {
            storage.delete(bucket);
        }
    }
}

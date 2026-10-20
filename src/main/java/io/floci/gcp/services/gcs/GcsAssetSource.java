package io.floci.gcp.services.gcs;

import io.floci.gcp.services.cloudasset.Asset;
import io.floci.gcp.services.cloudasset.AssetSource;
import io.floci.gcp.services.gcs.model.GcsBucket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Locale;

@ApplicationScoped
public class GcsAssetSource implements AssetSource {

    private final GcsService service;

    @Inject
    public GcsAssetSource(GcsService service) {
        this.service = service;
    }

    @Override
    public List<Asset> assets(String projectId) {
        return service.listBuckets(projectId).stream().map(GcsAssetSource::asset).toList();
    }

    private static Asset asset(GcsBucket bucket) {
        String location = bucket.getLocation() == null ? null : bucket.getLocation().toLowerCase(Locale.ROOT);
        return new Asset("//storage.googleapis.com/" + bucket.getName(), "storage.googleapis.com/Bucket",
                bucket.getProjectId(), bucket.getName(), location, bucket.getLabels(), bucket.getTimeCreated(),
                bucket.getUpdated(), List.of("//storage.googleapis.com/projects/_/buckets/" + bucket.getName()));
    }
}

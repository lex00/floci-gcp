package io.floci.gcp.services.pubsub;

import io.floci.gcp.services.cloudasset.Asset;
import io.floci.gcp.services.cloudasset.AssetSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class PubSubAssetSource implements AssetSource {

    private final PubSubService service;

    @Inject
    public PubSubAssetSource(PubSubService service) {
        this.service = service;
    }

    @Override
    public List<Asset> assets(String projectId) {
        List<Asset> assets = new ArrayList<>();
        service.listTopics(projectId).forEach(topic ->
                assets.add(asset(topic.getName(), "pubsub.googleapis.com/Topic", projectId, topic.getLabels())));
        service.listSubscriptions(projectId).forEach(subscription -> assets.add(asset(subscription.getName(),
                "pubsub.googleapis.com/Subscription", projectId, subscription.getLabels())));
        return assets;
    }

    private static Asset asset(String resourceName, String assetType, String projectId, Map<String, String> labels) {
        String name = "//pubsub.googleapis.com/" + resourceName;
        return new Asset(name, assetType, projectId, resourceName.substring(resourceName.lastIndexOf('/') + 1),
                null, labels, null, null, List.of(name));
    }
}

package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.services.cloudasset.Asset;
import io.floci.gcp.services.cloudasset.AssetSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@ApplicationScoped
public class ComputeAssetSource implements AssetSource {

    private static final String PREFIX = "//compute.googleapis.com/projects/";

    private final ComputeService service;

    @Inject
    public ComputeAssetSource(ComputeService service) {
        this.service = service;
    }

    @Override
    public List<Asset> assets(String projectId) {
        List<Asset> assets = new ArrayList<>();
        service.resources(projectId).forEach((key, resource) -> {
            String assetType = assetType(key);
            if (assetType == null) {
                return;
            }
            String[] parts = key.split("/");
            String scope = parts.length == 4 ? parts[1] : "global";
            String name = PREFIX + projectId + "/" + key;
            String byId = PREFIX + projectId + "/" + key.substring(0, key.lastIndexOf('/') + 1) + resource.path("id").asText();
            assets.add(new Asset(name, assetType, projectId, parts[parts.length - 1], scope, labels(resource),
                    textOrNull(resource, "creationTimestamp"), null, List.of(name, byId)));
        });
        return assets;
    }

    /** Asset types follow the Cloud Asset Inventory resource name format for the collections the emulator serves. */
    private static String assetType(String key) {
        String[] parts = key.split("/");
        if (parts.length == 3 && parts[0].equals("global")) {
            return switch (parts[1]) {
                case "networks" -> "compute.googleapis.com/Network";
                case "firewalls" -> "compute.googleapis.com/Firewall";
                case "addresses" -> "compute.googleapis.com/GlobalAddress";
                default -> null;
            };
        }
        if (parts.length == 4 && parts[0].equals("regions")) {
            return switch (parts[2]) {
                case "subnetworks" -> "compute.googleapis.com/Subnetwork";
                case "addresses" -> "compute.googleapis.com/Address";
                default -> null;
            };
        }
        if (parts.length == 4 && parts[0].equals("zones")) {
            return switch (parts[2]) {
                case "instances" -> "compute.googleapis.com/Instance";
                case "disks" -> "compute.googleapis.com/Disk";
                default -> null;
            };
        }
        return null;
    }

    private static Map<String, String> labels(ObjectNode resource) {
        Map<String, String> labels = new LinkedHashMap<>();
        resource.path("labels").properties().forEach(e -> labels.put(e.getKey(), e.getValue().asText()));
        return labels;
    }

    private static String textOrNull(ObjectNode resource, String field) {
        JsonNode value = resource.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }
}

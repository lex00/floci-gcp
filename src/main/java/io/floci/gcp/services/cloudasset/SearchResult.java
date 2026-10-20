package io.floci.gcp.services.cloudasset;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** An {@link Asset} joined with its project number and directly attached tags. */
record SearchResult(Asset asset, String projectNumber, List<String> tagKeys, List<String> tagValues,
                    List<String> tagValueIds, List<Map<String, String>> tags) {

    static final Set<String> FIELDS = Set.of("name", "assetType", "project", "displayName", "location", "labels",
            "tags", "tagKeys", "tagValues", "tagValueIds", "createTime", "updateTime");

    Map<String, Object> toJson(Set<String> readMask) {
        Map<String, Object> json = new LinkedHashMap<>();
        put(json, readMask, "name", asset.name());
        put(json, readMask, "assetType", asset.assetType());
        put(json, readMask, "project", "projects/" + projectNumber);
        put(json, readMask, "displayName", asset.displayName());
        put(json, readMask, "location", asset.location());
        put(json, readMask, "labels", asset.labels() == null || asset.labels().isEmpty() ? null : asset.labels());
        put(json, readMask, "tags", tags.isEmpty() ? null : tags);
        put(json, readMask, "tagKeys", tagKeys.isEmpty() ? null : tagKeys);
        put(json, readMask, "tagValues", tagValues.isEmpty() ? null : tagValues);
        put(json, readMask, "tagValueIds", tagValueIds.isEmpty() ? null : tagValueIds);
        put(json, readMask, "createTime", asset.createTime());
        put(json, readMask, "updateTime", asset.updateTime());
        return json;
    }

    String sortValue(String field) {
        return switch (field) {
            case "name" -> asset.name();
            case "assetType" -> asset.assetType();
            case "project" -> projectNumber;
            case "displayName" -> asset.displayName();
            case "location" -> asset.location();
            case "createTime" -> asset.createTime();
            case "updateTime" -> asset.updateTime();
            default -> null;
        };
    }

    private static void put(Map<String, Object> json, Set<String> readMask, String field, Object value) {
        if (value != null && (readMask == null || readMask.contains(field))) {
            json.put(field, value);
        }
    }
}

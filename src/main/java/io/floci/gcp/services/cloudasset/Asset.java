package io.floci.gcp.services.cloudasset;

import java.util.List;
import java.util.Map;

/**
 * A resource as Cloud Asset Inventory reports it. {@code tagParents} are the full resource names a tag
 * binding may use for it, which differ from {@code name} for buckets and add the numeric ID for Compute.
 */
public record Asset(String name, String assetType, String projectId, String displayName, String location,
                    Map<String, String> labels, String createTime, String updateTime, List<String> tagParents) {}

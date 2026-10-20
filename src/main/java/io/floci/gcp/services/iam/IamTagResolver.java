package io.floci.gcp.services.iam;

import java.util.List;

/** Supplies the tags IAM Conditions match with {@code resource.matchTag} and {@code resource.matchTagId}. */
public interface IamTagResolver {

    /**
     * The effective tags on a resource: those bound to it directly and those it inherits from its project,
     * where a direct value replaces the inherited one for the same key.
     */
    List<IamTag> effectiveTags(IamResource resource);

    /** Rewrites a namespaced key whose parent is a project number to use the project ID. */
    String canonicalKey(String namespacedKey);

    /**
     * @param key           the key's namespaced name, {@code {parentId}/{shortName}}
     * @param keyName       {@code tagKeys/{id}}
     * @param value         the value's namespaced name, {@code {parentId}/{keyShortName}/{shortName}}
     * @param valueName     {@code tagValues/{id}}
     */
    record IamTag(String key, String keyName, String value, String valueName) {}
}

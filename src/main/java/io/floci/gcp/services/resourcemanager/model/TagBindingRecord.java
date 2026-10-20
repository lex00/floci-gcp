package io.floci.gcp.services.resourcemanager.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** A Resource Manager v3 TagBinding, keyed by its {@code tagBindings/...} name. */
@RegisterForReflection
public class TagBindingRecord {

    private String name;
    private String parent;
    private String tagValue;
    private String tagValueNamespacedName;

    public TagBindingRecord() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getParent() { return parent; }
    public void setParent(String parent) { this.parent = parent; }

    public String getTagValue() { return tagValue; }
    public void setTagValue(String tagValue) { this.tagValue = tagValue; }

    public String getTagValueNamespacedName() { return tagValueNamespacedName; }
    public void setTagValueNamespacedName(String tagValueNamespacedName) { this.tagValueNamespacedName = tagValueNamespacedName; }
}

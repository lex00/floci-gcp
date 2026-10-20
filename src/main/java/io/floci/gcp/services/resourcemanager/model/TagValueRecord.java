package io.floci.gcp.services.resourcemanager.model;

import io.quarkus.runtime.annotations.RegisterForReflection;

/** A Resource Manager v3 TagValue, keyed by {@code tagValues/{id}}. */
@RegisterForReflection
public class TagValueRecord {

    private String name;
    private String parent;
    private String shortName;
    private String namespacedName;
    private String description;
    private String etag;
    private String createTime;
    private String updateTime;

    public TagValueRecord() {}

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getParent() { return parent; }
    public void setParent(String parent) { this.parent = parent; }

    public String getShortName() { return shortName; }
    public void setShortName(String shortName) { this.shortName = shortName; }

    public String getNamespacedName() { return namespacedName; }
    public void setNamespacedName(String namespacedName) { this.namespacedName = namespacedName; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getEtag() { return etag; }
    public void setEtag(String etag) { this.etag = etag; }

    public String getCreateTime() { return createTime; }
    public void setCreateTime(String createTime) { this.createTime = createTime; }

    public String getUpdateTime() { return updateTime; }
    public void setUpdateTime(String updateTime) { this.updateTime = updateTime; }
}

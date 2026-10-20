package io.floci.gcp.services.resourcemanager;

import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProjectRecord;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.services.compute.ComputeService;
import io.floci.gcp.services.iam.IamResource;
import io.floci.gcp.services.iam.IamTagResolver;
import io.floci.gcp.services.resourcemanager.model.TagBindingRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Effective tags for IAM Conditions: the bindings on the resource's project, replaced key by key by the
 * bindings on the resource itself. A Cloud Storage object takes its bucket's bindings, and a Compute
 * resource also matches bindings that name its numeric ID.
 */
@ApplicationScoped
public class TagIamResolver implements IamTagResolver {

    private static final String BUCKET_PREFIX = "projects/_/buckets/";

    private final TagService tags;
    private final ProjectRegistry projects;
    private final ComputeService compute;

    @Inject
    public TagIamResolver(TagService tags, ProjectRegistry projects, ComputeService compute) {
        this.tags = tags;
        this.projects = projects;
        this.compute = compute;
    }

    @Override
    public List<IamTag> effectiveTags(IamResource resource) {
        Map<String, IamTag> byKey = new LinkedHashMap<>();
        if (resource.projectResource() != null) {
            addBindings(byKey, "//cloudresourcemanager.googleapis.com/" + resource.projectResource());
        }
        for (String parent : directParents(resource)) {
            addBindings(byKey, parent);
        }
        return List.copyOf(byKey.values());
    }

    @Override
    public String canonicalKey(String namespacedKey) {
        try {
            return tags.canonicalNamespacedName(namespacedKey);
        } catch (GcpException e) {
            return namespacedKey;
        }
    }

    private List<String> directParents(IamResource resource) {
        List<String> parents = new ArrayList<>();
        String name = resource.name();
        if (name.equals(resource.projectResource())) {
            return parents;
        }
        if (resource.service().equals("storage.googleapis.com") && name.startsWith(BUCKET_PREFIX)) {
            int objects = name.indexOf("/objects/");
            parents.add("//storage.googleapis.com/" + (objects < 0 ? name : name.substring(0, objects)));
            return parents;
        }
        parents.add("//" + resource.service() + "/" + name);
        if (resource.service().equals("compute.googleapis.com") && resource.projectResource() != null) {
            String projectId = resource.projectResource().substring("projects/".length());
            String path = name.substring(resource.projectResource().length() + 1);
            try {
                String id = compute.get(projects.find(projectId).map(ProjectRecord::getProjectId).orElse(projectId),
                        path, Map.of()).path("id").asText("");
                if (!id.isEmpty()) {
                    parents.add("//compute.googleapis.com/" + resource.projectResource() + "/"
                            + path.substring(0, path.lastIndexOf('/') + 1) + id);
                }
            } catch (GcpException ignored) {
                // A resource that does not exist yet (an insert) has no bindings by ID.
            }
        }
        return parents;
    }

    private void addBindings(Map<String, IamTag> byKey, String parent) {
        Map<String, IamTag> direct = new LinkedHashMap<>();
        for (TagBindingRecord binding : tags.bindingsFor(parent)) {
            tags.findValue(binding.getTagValue()).ifPresent(value -> {
                String key = value.getNamespacedName().substring(0, value.getNamespacedName().lastIndexOf('/'));
                direct.put(key, new IamTag(key, value.getParent(), value.getNamespacedName(), value.getName()));
            });
        }
        byKey.putAll(direct);
    }
}

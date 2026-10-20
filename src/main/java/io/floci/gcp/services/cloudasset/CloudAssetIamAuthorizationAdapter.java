package io.floci.gcp.services.cloudasset;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.gcp.core.common.ProjectRecord;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.services.iam.IamResource;
import io.floci.gcp.services.iam.authorization.IamAuthorizationAdapter;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.iam.authorization.IamPermissionCheck;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * searchAllResources needs {@code cloudasset.assets.searchAllResources} on the scope, and every Cloud Asset
 * Inventory call needs {@code serviceusage.services.use}, per the Cloud Asset Inventory access control reference.
 */
@ApplicationScoped
public class CloudAssetIamAuthorizationAdapter implements IamAuthorizationAdapter {

    static final String SEARCH = "cloudasset.assets.searchAllResources";
    static final String USE_SERVICE = "serviceusage.services.use";

    private final ProjectRegistry projects;

    @Inject
    public CloudAssetIamAuthorizationAdapter(ProjectRegistry projects) {
        this.projects = projects;
    }

    @Override
    public String serviceName() {
        return "cloudasset";
    }

    @Override
    public Set<Class<?>> restControllers() {
        return Set.of(CloudAssetController.class);
    }

    @Override
    public Set<String> grpcServices() {
        return Set.of();
    }

    @Override
    public IamOperation restOperation(String method, Map<String, String> path, JsonNode body) {
        if (!method.equals("searchAllResources") || !"projects".equals(path.get("scopeType"))) {
            throw unsupported("operation " + method + " on " + path.get("scopeType"));
        }
        String project = projects.find(path.get("scopeId")).map(ProjectRecord::getProjectId).orElse(path.get("scopeId"));
        return new IamOperation("SearchAllResources", "projects/" + project);
    }

    @Override
    public IamOperation grpcOperation(String method, JsonNode request) {
        throw unsupported("gRPC operation " + method);
    }

    @Override
    public List<IamPermissionCheck> checks(IamOperation operation) {
        if (!operation.name().equals("SearchAllResources")) {
            throw unsupported("operation " + operation.name());
        }
        IamResource project = requireResource(operation.resource());
        return List.of(new IamPermissionCheck(SEARCH, project), new IamPermissionCheck(USE_SERVICE, project));
    }

    @Override
    public Optional<IamResource> resource(String name) {
        return Optional.empty();
    }

    @Override
    public IamResource requireResource(String name) {
        if (name != null && name.matches("projects/[^/:]+")) {
            return IamResource.project(name);
        }
        return IamAuthorizationAdapter.super.requireResource(name);
    }

    @Override
    public Map<String, Set<String>> roles() {
        return Map.of("roles/cloudasset.viewer", Set.of(SEARCH),
                "roles/cloudasset.owner", Set.of(SEARCH),
                "roles/serviceusage.serviceUsageConsumer", Set.of(USE_SERVICE));
    }

    @Override
    public Map<String, Set<String>> basicRoles() {
        return Map.of("roles/owner", Set.of(SEARCH, USE_SERVICE),
                "roles/editor", Set.of(SEARCH, USE_SERVICE),
                "roles/viewer", Set.of(SEARCH));
    }
}

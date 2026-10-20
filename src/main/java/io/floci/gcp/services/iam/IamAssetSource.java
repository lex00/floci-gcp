package io.floci.gcp.services.iam;

import io.floci.gcp.services.cloudasset.Asset;
import io.floci.gcp.services.cloudasset.AssetSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;
import java.util.Map;

@ApplicationScoped
public class IamAssetSource implements AssetSource {

    private final IamService service;

    @Inject
    public IamAssetSource(IamService service) {
        this.service = service;
    }

    @Override
    public List<Asset> assets(String projectId) {
        return service.listServiceAccounts(projectId).stream().map(account -> {
            String name = "//iam.googleapis.com/projects/" + projectId + "/serviceAccounts/" + account.getUniqueId();
            return new Asset(name, "iam.googleapis.com/ServiceAccount", projectId, account.getDisplayName(), "global",
                    Map.of(), null, null, List.of(name));
        }).toList();
    }
}

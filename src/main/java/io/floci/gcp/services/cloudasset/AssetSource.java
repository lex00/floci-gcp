package io.floci.gcp.services.cloudasset;

import java.util.List;

/** A service whose resources Cloud Asset Inventory search returns. */
public interface AssetSource {

    /** The resources this source holds in the project, by project ID. */
    List<Asset> assets(String projectId);
}

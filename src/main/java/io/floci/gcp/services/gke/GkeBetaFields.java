package io.floci.gcp.services.gke;

import io.floci.gcp.core.common.GcpException;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code google.container.v1beta1} fields that {@code google.container.v1} lacks. floci-gcp
 * implements the v1 surface, so a v1beta1 request that sets one of these answers 400 naming the
 * field. A field holding only its proto3 default ({@code false}, {@code 0}, {@code ""}, an
 * {@code *_UNSPECIFIED} enum, or an object of defaults) counts as unset, since clients built from
 * {@code google-beta} send several of them that way.
 */
final class GkeBetaFields {

    static final String V1BETA1 = "v1beta1";

    private static final Set<String> CLUSTER = Set.of("clusterTelemetry", "currentEmulatedVersion", "master",
            "masterIpv4CidrBlock", "nodePoolUpgradeConcurrencyConfig", "podSecurityPolicyConfig", "privateCluster",
            "protectConfig", "rollbackSafeUpgrade", "tpuConfig", "workloadAltsConfig", "workloadCertificates");
    private static final Set<String> CLUSTER_UPDATE = Set.of("desiredClusterTelemetry",
            "desiredHostMaintenancePolicy", "desiredMaster", "desiredNodePoolUpgradeConcurrencyConfig",
            "desiredPodSecurityPolicyConfig", "desiredProtectConfig", "desiredRollbackSafeUpgrade",
            "desiredScheduleUpgradeConfig", "desiredTpuConfig", "desiredWorkloadAltsConfig",
            "desiredWorkloadCertificates", "privateClusterConfig");
    private static final Set<String> NODE_CONFIG = Set.of("ephemeralStorageConfig", "hostMaintenancePolicy");
    private static final Set<String> ADDONS_CONFIG = Set.of("istioConfig", "kalmConfig");

    private GkeBetaFields() {
    }

    static void checkCluster(String version, Map<String, Object> cluster) {
        if (!V1BETA1.equals(version) || cluster == null) {
            return;
        }
        reject("cluster", cluster, CLUSTER);
        reject("cluster.nodeConfig", child(cluster, "nodeConfig"), NODE_CONFIG);
        reject("cluster.addonsConfig", child(cluster, "addonsConfig"), ADDONS_CONFIG);
        if (cluster.get("nodePools") instanceof List<?> pools) {
            for (int i = 0; i < pools.size(); i++) {
                if (pools.get(i) instanceof Map<?, ?> pool) {
                    reject("cluster.nodePools[" + i + "].config", child(pool, "config"), NODE_CONFIG);
                }
            }
        }
    }

    static void checkClusterUpdate(String version, Map<String, Object> update) {
        if (!V1BETA1.equals(version) || update == null) {
            return;
        }
        reject("update", update, CLUSTER_UPDATE);
        reject("update.desiredAddonsConfig", child(update, "desiredAddonsConfig"), ADDONS_CONFIG);
    }

    static void checkNodePool(String version, Map<String, Object> nodePool) {
        if (!V1BETA1.equals(version) || nodePool == null) {
            return;
        }
        reject("nodePool.config", child(nodePool, "config"), NODE_CONFIG);
    }

    private static Map<?, ?> child(Map<?, ?> parent, String field) {
        return parent.get(field) instanceof Map<?, ?> map ? map : null;
    }

    private static void reject(String path, Map<?, ?> message, Set<String> betaOnly) {
        if (message == null) {
            return;
        }
        for (String field : betaOnly) {
            if (isSet(message.get(field))) {
                throw GcpException.invalidArgument("Field " + path + "." + field
                        + " is a v1beta1 field that floci-gcp does not implement.");
            }
        }
    }

    private static boolean isSet(Object value) {
        if (value == null || Boolean.FALSE.equals(value)) {
            return false;
        }
        if (value instanceof Number number) {
            return number.doubleValue() != 0;
        }
        if (value instanceof String text) {
            return !text.isEmpty() && !text.endsWith("_UNSPECIFIED");
        }
        if (value instanceof List<?> list) {
            return list.stream().anyMatch(GkeBetaFields::isSet);
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(GkeBetaFields::isSet);
        }
        return true;
    }
}

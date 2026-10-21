package io.floci.gcp.services.cloudrun;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.cloud.run.v2.VpcAccess;
import io.floci.gcp.core.common.GcpException;

import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Checks Direct VPC egress against the Compute networks and subnetworks floci-gcp serves. A
 * network or subnetwork is a name, a partial URL or a full URL, and may live in a Shared VPC host
 * project named by its URL.
 */
final class CloudRunVpcAccess {

    private static final Pattern NETWORK = Pattern.compile("(?:.*/)?projects/([^/]+)/global/networks/([^/]+)");
    private static final Pattern SUBNETWORK = Pattern.compile("(?:.*/)?projects/([^/]+)/regions/([^/]+)/subnetworks/([^/]+)");
    private static final Pattern NAME = Pattern.compile("[a-z](?:[-a-z0-9]{0,61}[a-z0-9])?");

    private CloudRunVpcAccess() {}

    /**
     * Refuses a network interface whose network or subnetwork the project does not hold, or whose
     * subnetwork is outside {@code location}. {@code resources} maps a project to its Compute
     * resources keyed by path under the project.
     */
    static void require(VpcAccess access, String project, String location,
                        Function<String, Map<String, ObjectNode>> resources) {
        if (access.getEgress() == VpcAccess.VpcEgress.UNRECOGNIZED) {
            throw GcpException.invalidArgument("vpcAccess.egress must be ALL_TRAFFIC or PRIVATE_RANGES_ONLY");
        }
        for (VpcAccess.NetworkInterface nic : access.getNetworkInterfacesList()) {
            if (nic.getNetwork().isBlank() && nic.getSubnetwork().isBlank()) {
                throw GcpException.invalidArgument("A VPC network interface names a network or a subnetwork");
            }
            ObjectNode network = null;
            if (!nic.getNetwork().isBlank()) {
                String[] ref = reference(nic.getNetwork(), NETWORK, project, 2, "network");
                network = resources.apply(ref[0]).get("global/networks/" + ref[1]);
                if (network == null) {
                    throw GcpException.invalidArgument("VPC network " + nic.getNetwork() + " does not exist");
                }
            }
            // Without a subnetwork, Cloud Run uses the subnetwork named like the network.
            String subnetRef = nic.getSubnetwork().isBlank() ? network.path("name").asText() : nic.getSubnetwork();
            String[] ref = reference(subnetRef, SUBNETWORK, project, 3, "subnetwork");
            String region = ref.length == 3 ? ref[1] : location;
            if (!region.equals(location)) {
                throw GcpException.invalidArgument("Subnetwork " + subnetRef + " is not in region " + location);
            }
            ObjectNode subnet = resources.apply(ref[0]).get("regions/" + location + "/subnetworks/" + ref[ref.length - 1]);
            if (subnet == null) {
                throw GcpException.invalidArgument("Subnetwork " + subnetRef + " does not exist in region " + location);
            }
            if (network != null && !subnet.path("network").asText().equals(network.path("selfLink").asText())) {
                throw GcpException.invalidArgument("Subnetwork " + subnetRef + " is not in network " + nic.getNetwork());
            }
        }
    }

    /** {project, name} for a bare name, else the URL's captured groups. */
    private static String[] reference(String value, Pattern url, String project, int groups, String field) {
        Matcher match = url.matcher(value);
        if (match.matches()) {
            String[] parts = new String[groups];
            for (int i = 0; i < groups; i++) {
                parts[i] = match.group(i + 1);
            }
            return parts;
        }
        if (!NAME.matcher(value).matches()) {
            throw GcpException.invalidArgument("Invalid VPC " + field + ": " + value);
        }
        return new String[]{project, value};
    }
}

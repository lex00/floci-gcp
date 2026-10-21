package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.core.common.GcpException;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.InetAddress;
import java.util.*;
import static io.floci.gcp.services.compute.ComputeService.*;

@ApplicationScoped
public class ComputeRoutingResources implements ComputeResourceHandler {
    private static final Set<String> ZONAL_NEG_TYPES = Set.of("GCE_VM_IP_PORT", "NON_GCP_PRIVATE_IP_PORT");
    private static final Set<String> REGIONAL_NEG_TYPES = Set.of("INTERNET_IP_PORT", "INTERNET_FQDN_PORT");
    private static final Set<String> GLOBAL_NEG_TYPES = Set.of("INTERNET_IP_PORT", "INTERNET_FQDN_PORT");
    // Internet NEG backends take no health check.
    private static final Set<String> UNCHECKED_NEG_TYPES = Set.of("INTERNET_IP_PORT", "INTERNET_FQDN_PORT");
    private static final String FQDN = "(?=.{1,253}\\.?$)[A-Za-z0-9](?:[-A-Za-z0-9]{0,61}[A-Za-z0-9])?(?:\\.[A-Za-z0-9](?:[-A-Za-z0-9]{0,61}[A-Za-z0-9])?)+\\.?";
    public boolean handles(String kind) {
        return Set.of("networkEndpointGroups", "healthChecks", "backendServices", "urlMaps", "targetHttpProxies", "forwardingRules").contains(kind);
    }
    public void create(ComputeService.Context c, ObjectNode r) {
        validate(c, r, true);
        if (Set.of("backendServices", "urlMaps", "forwardingRules").contains(c.collection())) { r.put("fingerprint", fingerprint()); }
        if (c.collection().equals("networkEndpointGroups")) { r.put("size", 0); c.state.endpoints.put(c.key(), new ArrayList<>()); }
    }
    private void validate(ComputeService.Context c, ObjectNode r, boolean creating) {
        if (c.collection().equals("networkEndpointGroups")) { endpointGroup(c, r); return; }
        c.scope("global");
        switch (c.collection()) {
            case "healthChecks" -> {
                if (!r.path("type").asText().equals("HTTP")) { throw GcpException.unimplemented("Only HTTP health check configuration is supported"); }
                ObjectNode check = objectField(r, "httpHealthCheck");
                check.put("port", check.path("port").asInt(80)); port(check.path("port"));
                check.put("requestPath", check.path("requestPath").asText("/"));
                r.put("checkIntervalSec", integer(r.path("checkIntervalSec").asText("5"), 1, 300, "checkIntervalSec"));
                r.put("timeoutSec", integer(r.path("timeoutSec").asText("5"), 1, r.path("checkIntervalSec").asInt(), "timeoutSec"));
            }
            case "backendServices" -> {
                if (!r.path("loadBalancingScheme").asText("EXTERNAL_MANAGED").equals("EXTERNAL_MANAGED")
                        || !r.path("protocol").asText("HTTP").equals("HTTP")) { throw GcpException.unimplemented("Only global external managed HTTP backends are supported"); }
                r.put("loadBalancingScheme", "EXTERNAL_MANAGED").put("protocol", "HTTP");
                Set<String> groups = new HashSet<>();
                int unchecked = 0;
                List<ObjectNode> backends = objectArray(r, "backends");
                for (ObjectNode backend : backends) {
                    ObjectNode group = c.reference(backend, "group", "networkEndpointGroups");
                    if (!groups.add(group.path("selfLink").asText())) { throw GcpException.invalidArgument("Duplicate backend group"); }
                    if (UNCHECKED_NEG_TYPES.contains(group.path("networkEndpointType").asText())) {
                        unchecked++;
                        if (!backend.path("balancingMode").asText("UTILIZATION").matches("RATE|UTILIZATION")) { throw GcpException.unimplemented("Unsupported backend balancingMode"); }
                        continue;
                    }
                    if (!backend.path("balancingMode").asText("RATE").equals("RATE")) { throw GcpException.unimplemented("Only RATE backend balancing is supported"); }
                    backend.put("balancingMode", "RATE");
                    if (backend.path("maxRatePerEndpoint").asDouble(1) <= 0) { throw GcpException.invalidArgument("maxRatePerEndpoint must be positive"); }
                }
                if (unchecked > 0 && unchecked < backends.size()) { throw GcpException.invalidArgument("Internet NEG backends cannot be mixed with other backends"); }
                if (unchecked > 0) {
                    if (!r.path("healthChecks").isEmpty()) { throw GcpException.invalidArgument("Backend services with internet NEG backends take no health checks"); }
                } else {
                    if (!r.path("healthChecks").isArray() || r.path("healthChecks").size() != 1) { throw GcpException.invalidArgument("Exactly one health check is required"); }
                    ArrayNode checks = r.withArray("healthChecks");
                    for (int i = 0; i < checks.size(); i++) {
                        ObjectNode wrapper = object().put("check", checks.get(i).asText());
                        c.reference(wrapper, "check", "healthChecks"); checks.set(i, wrapper.get("check"));
                    }
                }
                if (!Set.of("NONE", "CLIENT_IP", "GENERATED_COOKIE", "HTTP_COOKIE").contains(r.path("sessionAffinity").asText("NONE"))) { throw GcpException.unimplemented("Unsupported sessionAffinity"); }
            }
            case "urlMaps" -> {
                c.reference(r, "defaultService", "backendServices");
                Set<String> matchers = new HashSet<>();
                for (ObjectNode matcher : objectArray(r, "pathMatchers")) {
                    name(required(matcher, "name"));
                    if (!matchers.add(matcher.path("name").asText())) { throw GcpException.invalidArgument("Duplicate path matcher"); }
                    c.reference(matcher, "defaultService", "backendServices");
                    for (ObjectNode rule : objectArray(matcher, "pathRules")) {
                        c.reference(rule, "service", "backendServices");
                        if (rule.path("paths").isEmpty()) { throw GcpException.invalidArgument("Path rules require paths"); }
                        for (JsonNode path : rule.path("paths")) {
                            if (!path.asText().startsWith("/") || path.asText().contains("?")) { throw GcpException.invalidArgument("Invalid path rule"); }
                        }
                    }
                    if (!matcher.path("routeRules").isEmpty()) { throw GcpException.unimplemented("Advanced routeRules are not implemented; use pathRules"); }
                }
                for (ObjectNode host : objectArray(r, "hostRules")) {
                    if (!matchers.contains(host.path("pathMatcher").asText())) { throw GcpException.invalidArgument("Host rule references a missing path matcher"); }
                }
            }
            case "targetHttpProxies" -> c.reference(r, "urlMap", "urlMaps");
            case "forwardingRules" -> {
                if (!r.path("loadBalancingScheme").asText().equals("EXTERNAL_MANAGED") || !r.path("IPProtocol").asText().equals("TCP")) { throw GcpException.unimplemented("Only EXTERNAL_MANAGED TCP forwarding rules are supported"); }
                c.reference(r, "target", "targetHttpProxies");
                int[] ports = portRange(r);
                if (!r.path("networkTier").asText("PREMIUM").equals("PREMIUM")) {
                    throw GcpException.invalidArgument("Global external forwarding rules require PREMIUM network tier");
                }
                r.put("networkTier", "PREMIUM");
                ObjectNode reservation = null;
                if (creating) {
                    if (r.has("IPAddress")) {
                        reservation = reservation(c, required(r, "IPAddress"));
                        r.put("IPAddress", reservation.path("address").asText());
                    } else { r.put("IPAddress", ComputeNetworkResources.allocateExternal(c)); }
                }
                for (var entry : c.state.resources.entrySet()) {
                    ObjectNode other = entry.getValue();
                    if (!entry.getKey().startsWith("global/forwardingRules/")
                            || other.path("selfLink").equals(r.path("selfLink"))
                            || !other.path("IPAddress").equals(r.path("IPAddress"))) { continue; }
                    if (!other.path("loadBalancingScheme").equals(r.path("loadBalancingScheme"))
                            || !other.path("networkTier").asText("PREMIUM").equals(r.path("networkTier").asText())) {
                        throw GcpException.invalidArgument("Shared forwarding address requires matching scheme and tier");
                    }
                    int[] otherPorts = portRange(other);
                    if (other.path("IPProtocol").equals(r.path("IPProtocol")) && ports[0] <= otherPorts[1] && otherPorts[0] <= ports[1]) {
                        throw GcpException.invalidArgument("Forwarding address protocol and ports are already in use");
                    }
                }
                if (reservation != null) { ComputeNetworkResources.claim(reservation, r.path("selfLink").asText()); }
            }
            default -> throw GcpException.unimplemented("Unknown routing resource");
        }
    }
    private void endpointGroup(ComputeService.Context c, ObjectNode r) {
        boolean zonal = c.scope().startsWith("zones/"), regional = c.scope().startsWith("regions/");
        Set<String> types = zonal ? ZONAL_NEG_TYPES : regional ? REGIONAL_NEG_TYPES : c.scope().equals("global") ? GLOBAL_NEG_TYPES : Set.of();
        if (types.isEmpty()) { throw GcpException.invalidArgument("Invalid scope for networkEndpointGroups"); }
        String type = r.path("networkEndpointType").asText(zonal ? "GCE_VM_IP_PORT" : "");
        if (!types.contains(type)) { throw GcpException.unimplemented("networkEndpointType " + type + " is not implemented for " + c.scope()); }
        r.put("networkEndpointType", type);
        switch (type) {
            case "GCE_VM_IP_PORT" -> {
                ObjectNode subnet = c.reference(r, "subnetwork", "subnetworks");
                c.reference(r, "network", "networks");
                if (!r.path("network").equals(subnet.path("network"))) { throw GcpException.invalidArgument("NEG network does not own subnet"); }
                String region = c.scope().substring(6, c.scope().lastIndexOf('-'));
                if (!subnet.path("region").asText().equals(c.link("regions/" + region))) { throw GcpException.invalidArgument("NEG and subnet regions differ"); }
            }
            case "NON_GCP_PRIVATE_IP_PORT" -> {
                c.reference(r, "network", "networks");
                if (r.has("subnetwork")) { throw GcpException.invalidArgument("NON_GCP_PRIVATE_IP_PORT network endpoint groups take no subnetwork"); }
            }
            default -> {
                if (r.has("subnetwork") || (!regional && r.has("network"))) {
                    throw GcpException.invalidArgument(type + " network endpoint groups in " + c.scope() + " take no " + (r.has("subnetwork") ? "subnetwork" : "network"));
                }
                if (r.has("network")) { c.reference(r, "network", "networks"); }
            }
        }
        if (r.has("defaultPort")) { port(r.path("defaultPort")); }
    }
    private static void endpoint(ComputeService.Context c, ObjectNode group, ObjectNode endpoint) {
        String type = group.path("networkEndpointType").asText();
        if (type.equals("GCE_VM_IP_PORT")) {
            if (endpoint.has("fqdn")) { throw GcpException.invalidArgument("GCE_VM_IP_PORT endpoints take no fqdn"); }
            if (endpoint.has("instance") && !endpoint.path("instance").asText().contains("/")) { endpoint.put("instance", c.scope() + "/instances/" + endpoint.path("instance").asText()); }
            ObjectNode vm = c.reference(endpoint, "instance", "instances");
            if (!vm.path("zone").asText().equals(c.link(c.scope()))) { throw GcpException.invalidArgument("Endpoint and VM zones differ"); }
            JsonNode nic = vm.path("networkInterfaces").get(0);
            if (!nic.path("network").equals(group.path("network")) || !nic.path("subnetwork").equals(group.path("subnetwork"))) { throw GcpException.invalidArgument("Endpoint is outside NEG network/subnet"); }
            if (!endpoint.has("ipAddress")) { endpoint.put("ipAddress", nic.path("networkIP").asText()); }
            if (!endpoint.path("ipAddress").equals(nic.path("networkIP"))) { throw GcpException.invalidArgument("Endpoint IP does not belong to instance"); }
        } else {
            if (endpoint.has("instance")) { throw GcpException.invalidArgument(type + " endpoints take no instance"); }
            if (type.equals("INTERNET_FQDN_PORT")) {
                if (endpoint.has("ipAddress")) { throw GcpException.invalidArgument("INTERNET_FQDN_PORT endpoints take no ipAddress"); }
                if (!required(endpoint, "fqdn").matches(FQDN)) { throw GcpException.invalidArgument("Invalid fqdn"); }
            } else {
                if (endpoint.has("fqdn")) { throw GcpException.invalidArgument(type + " endpoints take no fqdn"); }
                ipAddress(required(endpoint, "ipAddress"));
            }
        }
        if (!endpoint.has("port") && group.has("defaultPort")) { endpoint.set("port", group.get("defaultPort")); }
        port(endpoint.path("port"));
    }
    private static void ipAddress(String value) {
        try {
            InetAddress.ofLiteral(value);
        } catch (IllegalArgumentException e) {
            throw GcpException.invalidArgument("Invalid ipAddress: " + value);
        }
    }
    public void update(ComputeService.Context c, ObjectNode r, ObjectNode body, String verb) {
        if (c.collection().equals("networkEndpointGroups")) { ComputeResourceHandler.super.update(c, r, body, verb); return; }
        if (r.has("fingerprint")) { checkFingerprint(r, body, "fingerprint"); }
        if (c.collection().equals("forwardingRules") && body.has("IPAddress")) {
            String requested = required(body, "IPAddress"), current = r.path("IPAddress").asText();
            String normalized = requested.equals(current) ? current : reservation(c, requested).path("address").asText();
            if (!normalized.equals(current)) { throw GcpException.invalidArgument("Forwarding rule IPAddress cannot be changed"); }
            body = body.deepCopy();
            body.put("IPAddress", current);
        }
        for (var e : body.properties()) {
            if (!Set.of("id", "kind", "selfLink", "creationTimestamp", "name").contains(e.getKey())) { r.set(e.getKey(), e.getValue()); }
        }
        validate(c, r, false);
        if (r.has("fingerprint")) { r.put("fingerprint", fingerprint()); }
    }
    public void action(ComputeService.Context c, ObjectNode r, String action, ObjectNode body, Map<String,String> query) {
        if (c.collection().equals("targetHttpProxies") && action.equals("setUrlMap")) {
            c.reference(body, "urlMap", "urlMaps"); r.set("urlMap", body.get("urlMap")); return;
        }
        if (c.collection().equals("forwardingRules") && action.equals("setTarget")) {
            c.reference(body, "target", "targetHttpProxies"); r.set("target", body.get("target")); return;
        }
        if (!c.collection().equals("networkEndpointGroups") || !Set.of("attachNetworkEndpoints", "detachNetworkEndpoints").contains(action)) {
            ComputeResourceHandler.super.action(c, r, action, body, query); return;
        }
        List<ObjectNode> endpoints = c.state.endpoints.get(c.key());
        if (!body.path("networkEndpoints").isArray() || body.path("networkEndpoints").isEmpty()) { throw GcpException.invalidArgument("networkEndpoints are required"); }
        for (ObjectNode endpoint : objectArray(body, "networkEndpoints")) {
            endpoint(c, r, endpoint);
            if (action.equals("attachNetworkEndpoints")) {
                if (!endpoints.contains(endpoint)) { endpoints.add(endpoint.deepCopy()); }
            } else if (!endpoints.remove(endpoint)) { throw GcpException.notFound("Network endpoint not found"); }
        }
        r.put("size", endpoints.size());
    }
    public void delete(ComputeService.Context c, ObjectNode r) {
        if (c.collection().equals("networkEndpointGroups")) { c.state.endpoints.remove(c.key()); }
        if (c.collection().equals("forwardingRules")) {
            for (ObjectNode address : c.state.resources.values()) {
                if (address.path("address").asText().equals(r.path("IPAddress").asText()) && address.path("kind").asText().equals("compute#address")) {
                    ComputeNetworkResources.release(address, r.path("selfLink").asText());
                }
            }
        }
    }
    private static ObjectNode reservation(ComputeService.Context c, String value) {
        ObjectNode address = c.state.resources.entrySet().stream()
                .filter(e -> e.getKey().startsWith("global/addresses/") && (e.getValue().path("address").asText().equals(value)
                        || e.getValue().path("selfLink").asText().equals(value) || e.getKey().equals(value)))
                .map(Map.Entry::getValue).findFirst().orElseThrow(() -> GcpException.invalidArgument("A global address reservation is required"));
        if (!address.path("addressType").asText().equals("EXTERNAL") || !address.path("ipVersion").asText().equals("IPV4")
                || !address.path("networkTier").asText("PREMIUM").equals("PREMIUM")) {
            throw GcpException.invalidArgument("A global external PREMIUM IPv4 address is required");
        }
        return address;
    }
    private static int[] portRange(ObjectNode r) {
        String[] values = required(r, "portRange").split("-", -1);
        int first = integer(values[0], 1, 65535, "portRange");
        if (values.length > 2) { throw GcpException.invalidArgument("Invalid portRange"); }
        int last = values.length == 2 ? integer(values[1], first, 65535, "portRange") : first;
        return new int[]{first, last};
    }
    private static void port(JsonNode value) { integer(value.asText(), 1, 65535, "port"); }
}

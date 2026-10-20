package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.JsonNode;
import io.floci.gcp.services.iam.IamResource;
import io.floci.gcp.services.iam.authorization.IamAuthorizationAdapter;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.iam.authorization.IamPermissionCheck;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Compute Engine REST operations on networks, subnetworks, firewalls, addresses, instances, disks, the
 * location and type catalogs, and operations. Permissions follow the "IAM Permissions" section and the
 * field-level authorization notes of each method's Compute v1 REST reference page. Images, snapshots and
 * the load balancing resources have no mapping, so they answer unsupported in enforce mode.
 */
@ApplicationScoped
public class ComputeIamAuthorizationAdapter implements IamAuthorizationAdapter {

    private static final String SERVICE = "compute.googleapis.com";
    private static final String FIELD_SEPARATOR = "\n";

    /** Method-level permission per operation, from the method's reference page. */
    private static final Map<String, String> METHOD = methodPermissions();

    /** Field-level permissions per operation and request field, from the same pages. */
    private static final Map<String, Map<String, String>> FIELDS = Map.of(
            "subnetworks.insert", Map.of("network", "compute.networks.updatePolicy"),
            "firewalls.insert", Map.of("network", "compute.networks.updatePolicy"),
            "firewalls.patch", Map.of("network", "compute.networks.updatePolicy"),
            "firewalls.update", Map.of("network", "compute.networks.updatePolicy"),
            "addresses.insert", Map.of("subnetwork", "compute.subnetworks.use", "labels", "compute.addresses.setLabels"),
            "globalAddresses.insert", Map.of("network", "compute.networks.use",
                    "labels", "compute.globalAddresses.setLabels"),
            "disks.insert", Map.of("labels", "compute.disks.setLabels", "sourceImage", "compute.images.useReadOnly",
                    "sourceSnapshot", "compute.snapshots.useReadOnly", "sourceDisk", "compute.disks.useReadOnly"),
            "instances.insert", Map.of("labels", "compute.instances.setLabels", "diskLabels", "compute.disks.setLabels",
                    "metadata", "compute.instances.setMetadata", "tags", "compute.instances.setTags",
                    "serviceAccounts", "compute.instances.setServiceAccount",
                    "deletionProtection", "compute.instances.setDeletionProtection",
                    "initializeParams", "compute.disks.create", "sourceImage", "compute.images.useReadOnly",
                    "sourceSnapshot", "compute.snapshots.useReadOnly"));

    /** Internal addresses take the createInternal variant the reference lists beside create. */
    private static final Map<String, String> INTERNAL_CREATE = Map.of(
            "addresses.insert", "compute.addresses.createInternal",
            "globalAddresses.insert", "compute.globalAddresses.createInternal");

    private static final Map<String, String> TYPES = Map.of("networks", "Network", "subnetworks", "Subnetwork",
            "firewalls", "Firewall", "addresses", "Address", "globalAddresses", "GlobalAddress",
            "instances", "Instance", "disks", "Disk", "images", "Image", "snapshots", "Snapshot");

    private static final Pattern RESOURCE = Pattern.compile("projects/([^/]+)/(?:"
            + "global/(networks|firewalls|addresses|snapshots)/([^/]+)"
            + "|global/(images)/((?:family/)?[^/]+)"
            + "|regions/[^/]+/(subnetworks|addresses)/([^/]+)"
            + "|zones/[^/]+/(instances|disks)/([^/]+))");

    private static final Set<String> CATALOGS = Set.of("machineTypes", "diskTypes", "acceleratorTypes");

    @Override
    public String serviceName() {
        return "compute";
    }

    @Override
    public Set<Class<?>> restControllers() {
        return Set.of(ComputeController.class);
    }

    @Override
    public Set<String> grpcServices() {
        return Set.of();
    }

    @Override
    public IamOperation restOperation(String method, Map<String, String> path, JsonNode body) {
        String project = path.get("project");
        String[] parts = path.get("path").split("/");
        String scope;
        int index;
        if ((parts[0].equals("regions") || parts[0].equals("zones")) && parts.length <= 2) {
            return catalogOperation(method, project, parts[0], parts.length == 2);
        } else if (parts[0].equals("global") || parts[0].equals("aggregated")) {
            scope = parts[0];
            index = 1;
        } else if (parts.length >= 3 && (parts[0].equals("regions") || parts[0].equals("zones"))) {
            scope = parts[0] + "/" + parts[1];
            index = 2;
        } else {
            throw unsupported("REST path " + path.get("path"));
        }
        if (parts.length <= index || parts.length > index + 3) {
            throw unsupported("REST path " + path.get("path"));
        }
        String collection = parts[index];
        String name = parts.length > index + 1 ? parts[index + 1] : null;
        String action = parts.length > index + 2 ? parts[index + 2] : null;
        String resource = resourceCollection(scope, collection);
        String verb = verb(method, resource, scope, name, action);
        String operation = resource + "." + verb;
        if (!METHOD.containsKey(operation)) {
            throw unsupported("REST operation " + operation);
        }
        String projectName = "projects/" + project;
        String target = switch (verb) {
            case "list", "aggregatedList" -> projectName;
            case "insert" -> body.hasNonNull("name") ? projectName + "/" + scopePath(scope) + collection + "/"
                    + body.get("name").asText() : projectName;
            default -> resource.endsWith("Operations") || CATALOGS.contains(collection)
                    ? projectName : projectName + "/" + scopePath(scope) + collection + "/" + name;
        };
        return new IamOperation(operation, target, String.join(FIELD_SEPARATOR, fields(operation, project, target, body)));
    }

    private IamOperation catalogOperation(String method, String project, String collection, boolean named) {
        if (!method.equals("get")) {
            throw unsupported("REST " + method + " on " + collection);
        }
        return new IamOperation(collection + "." + (named ? "get" : "list"), "projects/" + project);
    }

    private static String resourceCollection(String scope, String collection) {
        if (collection.equals("operations")) {
            return scope.startsWith("zones/") ? "zoneOperations"
                    : scope.startsWith("regions/") ? "regionOperations" : "globalOperations";
        }
        if (collection.equals("addresses") && scope.equals("global")) {
            return "globalAddresses";
        }
        return collection;
    }

    private String verb(String method, String resource, String scope, String name, String action) {
        if (scope.equals("aggregated")) {
            if (!method.equals("get") || name != null) {
                throw unsupported("REST " + method + " on aggregated " + resource);
            }
            return "aggregatedList";
        }
        return switch (method) {
            case "get" -> action != null ? fail(method, resource, action) : name == null ? "list" : "get";
            case "post" -> name == null ? "insert" : action != null ? action : fail(method, resource, null);
            case "delete" -> name != null && action == null ? "delete" : fail(method, resource, action);
            case "patch" -> name != null && action == null ? resource.equals("disks") ? "update" : "patch"
                    : fail(method, resource, action);
            case "put" -> name != null && action == null ? "update" : fail(method, resource, action);
            default -> fail(method, resource, action);
        };
    }

    private String fail(String method, String resource, String action) {
        throw unsupported("REST " + method + " on " + resource + (action == null ? "" : ":" + action));
    }

    private static String scopePath(String scope) {
        return scope.isEmpty() ? "" : scope + "/";
    }

    /** Request fields that carry their own permission, as {@code field=resource} entries. */
    private static List<String> fields(String operation, String project, String target, JsonNode body) {
        Map<String, String> declared = FIELDS.getOrDefault(operation, Map.of());
        List<String> entries = new ArrayList<>();
        if ((operation.equals("addresses.insert") || operation.equals("globalAddresses.insert"))
                && "INTERNAL".equals(body.path("addressType").asText())) {
            entries.add("internal=");
        }
        for (String field : List.of("network", "subnetwork", "sourceImage", "sourceSnapshot", "sourceDisk")) {
            if (declared.containsKey(field) && body.hasNonNull(field)) {
                entries.add(field + "=" + reference(project, body.get(field).asText()));
            }
        }
        for (String field : List.of("labels", "metadata", "tags", "serviceAccounts", "deletionProtection")) {
            if (declared.containsKey(field) && present(body.get(field))) {
                entries.add(field + "=");
            }
        }
        if (operation.equals("instances.insert")) {
            for (JsonNode attached : body.path("disks")) {
                JsonNode params = attached.path("initializeParams");
                if (params.isObject() && target.contains("/instances/")) {
                    String disk = target.substring(0, target.indexOf("/instances/")) + "/disks/"
                            + params.path("diskName").asText(target.substring(target.lastIndexOf('/') + 1));
                    entries.add("initializeParams=" + disk);
                    if (present(params.get("labels"))) {
                        entries.add("diskLabels=" + disk);
                    }
                    for (String field : List.of("sourceImage", "sourceSnapshot")) {
                        if (params.hasNonNull(field)) {
                            entries.add(field + "=" + reference(project, params.get(field).asText()));
                        }
                    }
                }
            }
        }
        return entries.stream().distinct().toList();
    }

    private static boolean present(JsonNode node) {
        return node != null && !node.isNull() && !(node.isContainerNode() && node.isEmpty())
                && !(node.isBoolean() && !node.asBoolean());
    }

    /** Full, project-relative or scope-relative reference to a {@code projects/...} resource name. */
    static String reference(String project, String ref) {
        int at = ref.indexOf("projects/");
        return at >= 0 ? ref.substring(at) : "projects/" + project + "/" + ref;
    }

    @Override
    public IamOperation grpcOperation(String method, JsonNode request) {
        throw unsupported("gRPC operation " + method);
    }

    @Override
    public List<IamPermissionCheck> checks(IamOperation operation) {
        String permission = METHOD.get(operation.name());
        if (permission == null) {
            throw unsupported("operation " + operation.name());
        }
        Map<String, String> fields = new LinkedHashMap<>();
        if (!operation.relatedResource().isEmpty()) {
            for (String entry : operation.relatedResource().split(FIELD_SEPARATOR)) {
                int eq = entry.indexOf('=');
                fields.put(entry.substring(0, eq), entry.substring(eq + 1));
            }
        }
        if (fields.containsKey("internal")) {
            permission = INTERNAL_CREATE.get(operation.name());
        }
        IamResource target = target(operation.resource());
        List<IamPermissionCheck> checks = new ArrayList<>();
        checks.add(new IamPermissionCheck(permission, target));
        Map<String, String> declared = FIELDS.getOrDefault(operation.name(), Map.of());
        fields.forEach((field, resource) -> {
            if (declared.containsKey(field)) {
                checks.add(new IamPermissionCheck(declared.get(field),
                        resource.isEmpty() ? target : requireResource(resource)));
            }
        });
        return checks;
    }

    private IamResource target(String name) {
        return name.matches("projects/[^/]+") ? IamResource.project(name) : requireResource(name);
    }

    @Override
    public Optional<IamResource> resource(String name) {
        Matcher m = RESOURCE.matcher(name);
        if (!m.matches()) {
            return Optional.empty();
        }
        String collection = m.group(2) != null ? m.group(2) : m.group(4) != null ? m.group(4)
                : m.group(6) != null ? m.group(6) : m.group(8);
        String type = collection.equals("addresses") && name.contains("/global/") ? TYPES.get("globalAddresses")
                : TYPES.get(collection);
        return Optional.of(IamResource.projectChild(SERVICE, type, name, name));
    }

    @Override
    public Map<String, Set<String>> roles() {
        return ComputeIamRoles.PREDEFINED;
    }

    @Override
    public Map<String, Set<String>> basicRoles() {
        Set<String> editor = ComputeIamRoles.PREDEFINED.get("roles/compute.admin");
        return Map.of("roles/viewer", ComputeIamRoles.PREDEFINED.get("roles/compute.viewer"),
                "roles/editor", editor, "roles/owner", editor);
    }

    /** Every permission the adapter can check, for the role catalog tests. */
    static Set<String> checkedPermissions() {
        Set<String> all = new HashSet<>(METHOD.values());
        all.addAll(INTERNAL_CREATE.values());
        FIELDS.values().forEach(fields -> all.addAll(fields.values()));
        return all;
    }

    private static Map<String, String> methodPermissions() {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("acceleratorTypes.aggregatedList", "compute.acceleratorTypes.list");
        m.put("acceleratorTypes.get", "compute.acceleratorTypes.get");
        m.put("acceleratorTypes.list", "compute.acceleratorTypes.list");
        m.put("addresses.aggregatedList", "compute.addresses.list");
        m.put("addresses.delete", "compute.addresses.delete");
        m.put("addresses.get", "compute.addresses.get");
        m.put("addresses.insert", "compute.addresses.create");
        m.put("addresses.list", "compute.addresses.list");
        m.put("addresses.setLabels", "compute.addresses.setLabels");
        m.put("diskTypes.aggregatedList", "compute.diskTypes.list");
        m.put("diskTypes.get", "compute.diskTypes.get");
        m.put("diskTypes.list", "compute.diskTypes.list");
        m.put("disks.aggregatedList", "compute.disks.list");
        m.put("disks.delete", "compute.disks.delete");
        m.put("disks.get", "compute.disks.get");
        m.put("disks.insert", "compute.disks.create");
        m.put("disks.list", "compute.disks.list");
        m.put("disks.resize", "compute.disks.resize");
        m.put("disks.setLabels", "compute.disks.setLabels");
        m.put("disks.update", "compute.disks.update");
        m.put("firewalls.delete", "compute.firewalls.delete");
        m.put("firewalls.get", "compute.firewalls.get");
        m.put("firewalls.insert", "compute.firewalls.create");
        m.put("firewalls.list", "compute.firewalls.list");
        m.put("firewalls.patch", "compute.firewalls.update");
        m.put("firewalls.update", "compute.firewalls.update");
        m.put("globalAddresses.delete", "compute.globalAddresses.delete");
        m.put("globalAddresses.get", "compute.globalAddresses.get");
        m.put("globalAddresses.insert", "compute.globalAddresses.create");
        m.put("globalAddresses.list", "compute.globalAddresses.list");
        m.put("globalAddresses.setLabels", "compute.globalAddresses.setLabels");
        m.put("globalOperations.aggregatedList", "compute.globalOperations.list");
        m.put("globalOperations.delete", "compute.globalOperations.delete");
        m.put("globalOperations.get", "compute.globalOperations.get");
        m.put("globalOperations.list", "compute.globalOperations.list");
        m.put("globalOperations.wait", "compute.globalOperations.get");
        m.put("instances.addAccessConfig", "compute.instances.addAccessConfig");
        m.put("instances.aggregatedList", "compute.instances.list");
        m.put("instances.attachDisk", "compute.instances.attachDisk");
        m.put("instances.delete", "compute.instances.delete");
        m.put("instances.deleteAccessConfig", "compute.instances.deleteAccessConfig");
        m.put("instances.detachDisk", "compute.instances.detachDisk");
        m.put("instances.get", "compute.instances.get");
        m.put("instances.insert", "compute.instances.create");
        m.put("instances.list", "compute.instances.list");
        m.put("instances.reset", "compute.instances.reset");
        m.put("instances.setDiskAutoDelete", "compute.instances.setDiskAutoDelete");
        m.put("instances.setLabels", "compute.instances.setLabels");
        m.put("instances.setMachineType", "compute.instances.setMachineType");
        m.put("instances.setMetadata", "compute.instances.setMetadata");
        m.put("instances.setTags", "compute.instances.setTags");
        m.put("instances.start", "compute.instances.start");
        m.put("instances.stop", "compute.instances.stop");
        m.put("machineTypes.aggregatedList", "compute.machineTypes.list");
        m.put("machineTypes.get", "compute.machineTypes.get");
        m.put("machineTypes.list", "compute.machineTypes.list");
        m.put("networks.delete", "compute.networks.delete");
        m.put("networks.get", "compute.networks.get");
        m.put("networks.insert", "compute.networks.create");
        m.put("networks.list", "compute.networks.list");
        m.put("networks.patch", "compute.networks.update");
        m.put("regionOperations.delete", "compute.regionOperations.delete");
        m.put("regionOperations.get", "compute.regionOperations.get");
        m.put("regionOperations.list", "compute.regionOperations.list");
        m.put("regionOperations.wait", "compute.regionOperations.get");
        m.put("regions.get", "compute.regions.get");
        m.put("regions.list", "compute.regions.list");
        m.put("subnetworks.aggregatedList", "compute.subnetworks.list");
        m.put("subnetworks.delete", "compute.subnetworks.delete");
        m.put("subnetworks.get", "compute.subnetworks.get");
        m.put("subnetworks.insert", "compute.subnetworks.create");
        m.put("subnetworks.list", "compute.subnetworks.list");
        m.put("subnetworks.patch", "compute.subnetworks.update");
        m.put("zoneOperations.delete", "compute.zoneOperations.delete");
        m.put("zoneOperations.get", "compute.zoneOperations.get");
        m.put("zoneOperations.list", "compute.zoneOperations.list");
        m.put("zoneOperations.wait", "compute.zoneOperations.get");
        m.put("zones.get", "compute.zones.get");
        m.put("zones.list", "compute.zones.list");
        return Map.copyOf(m);
    }
}

package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.iam.authorization.IamOperation;
import io.floci.gcp.services.iam.authorization.IamPermissionCheck;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputeIamAuthorizationAdapterServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final ComputeIamAuthorizationAdapter adapter = new ComputeIamAuthorizationAdapter();

    private List<String> checks(String method, String path, String body) throws Exception {
        JsonNode json = body == null ? JSON.createObjectNode() : JSON.readTree(body);
        IamOperation operation = adapter.restOperation(method, Map.of("project", "p", "path", path), json);
        return adapter.checks(operation).stream()
                .map(check -> check.permission() + " " + check.resource().name()).toList();
    }

    @Test
    void everyControllerHandlerReachesTheMap() throws Exception {
        Map<String, String> samples = Map.of("get", "global/networks/net", "post", "global/networks",
                "patch", "global/networks/net", "put", "global/firewalls/fw", "delete", "global/networks/net");
        List<Method> handlers = Arrays.stream(ComputeController.class.getDeclaredMethods())
                .filter(m -> m.isAnnotationPresent(GET.class) || m.isAnnotationPresent(POST.class)
                        || m.isAnnotationPresent(PATCH.class) || m.isAnnotationPresent(PUT.class)
                        || m.isAnnotationPresent(DELETE.class))
                .toList();
        assertEquals(samples.keySet(), new HashSet<>(handlers.stream().map(Method::getName).toList()));
        for (Method handler : handlers) {
            assertTrue(!checks(handler.getName(), samples.get(handler.getName()), "{\"name\":\"net\"}").isEmpty(),
                    handler.getName());
        }
    }

    @Test
    void methodAndFieldPermissionsFollowTheReferencePages() throws Exception {
        assertEquals(List.of("compute.subnetworks.create projects/p/regions/us-central1/subnetworks/sub",
                        "compute.networks.updatePolicy projects/p/global/networks/net"),
                checks("post", "regions/us-central1/subnetworks",
                        "{\"name\":\"sub\",\"network\":\"https://www.googleapis.com/compute/v1/projects/p/global/networks/net\"}"));
        assertEquals(List.of("compute.firewalls.update projects/p/global/firewalls/fw",
                        "compute.networks.updatePolicy projects/p/global/networks/net"),
                checks("patch", "global/firewalls/fw", "{\"network\":\"global/networks/net\"}"));
        assertEquals(List.of("compute.addresses.createInternal projects/p/regions/us-central1/addresses/a",
                        "compute.subnetworks.use projects/p/regions/us-central1/subnetworks/sub"),
                checks("post", "regions/us-central1/addresses",
                        "{\"name\":\"a\",\"addressType\":\"INTERNAL\",\"subnetwork\":\"regions/us-central1/subnetworks/sub\"}"));
        assertEquals(List.of("compute.instances.create projects/p/zones/us-central1-a/instances/vm",
                        "compute.instances.setLabels projects/p/zones/us-central1-a/instances/vm",
                        "compute.disks.create projects/p/zones/us-central1-a/disks/vm",
                        "compute.images.useReadOnly projects/debian-cloud/global/images/family/debian-12"),
                checks("post", "zones/us-central1-a/instances", "{\"name\":\"vm\",\"labels\":{\"env\":\"x\"},"
                        + "\"disks\":[{\"boot\":true,\"initializeParams\":{\"sourceImage\":"
                        + "\"projects/debian-cloud/global/images/family/debian-12\"}}]}"));
        assertEquals(List.of("compute.disks.update projects/p/zones/us-central1-a/disks/d"),
                checks("patch", "zones/us-central1-a/disks/d", "{}"));
        assertEquals(List.of("compute.zoneOperations.get projects/p"),
                checks("post", "zones/us-central1-a/operations/op-1/wait", null));
        assertEquals(List.of("compute.instances.list projects/p"), checks("get", "aggregated/instances", null));
        assertEquals(List.of("compute.machineTypes.get projects/p"),
                checks("get", "zones/us-central1-a/machineTypes/e2-small", null));
        assertEquals(List.of("compute.regions.list projects/p"), checks("get", "regions", null));
    }

    @Test
    void unmappedOperationsAreRefused() {
        for (List<String> request : List.of(List.of("post", "global/images"), List.of("get", "global/backendServices"),
                List.of("put", "global/networks/net"), List.of("post", "zones/us-central1-a/instances/vm/simulateMaintenanceEvent"),
                List.of("get", "global/routes"))) {
            GcpException error = assertThrows(GcpException.class, () -> checks(request.get(0), request.get(1), "{}"),
                    request.toString());
            assertTrue(error.getMessage().startsWith("IAM enforcement has no mapping for compute"), error.getMessage());
        }
    }

    @Test
    void rolesGrantOnlyCheckedPermissionsAndAdminGrantsThemAll() {
        Set<String> checked = ComputeIamAuthorizationAdapter.checkedPermissions();
        adapter.roles().forEach((role, grants) -> assertTrue(checked.containsAll(grants), role));
        assertEquals(checked, adapter.roles().get("roles/compute.admin"));
        assertTrue(adapter.basicRoles().get("roles/viewer").stream()
                .allMatch(p -> p.endsWith(".get") || p.endsWith(".list")));
    }

    @Test
    void resourcesInheritTheirProjectPolicy() {
        IamPermissionCheck check = adapter.checks(new IamOperation("networks.get", "projects/p/global/networks/net"))
                .getFirst();
        assertEquals("compute.googleapis.com/Network", check.resource().type());
        assertEquals("projects/p", check.resource().projectResource());
        ObjectNode body = JSON.createObjectNode();
        assertEquals("compute.googleapis.com/GlobalAddress",
                adapter.checks(adapter.restOperation("get", Map.of("project", "p", "path", "global/addresses/a"), body))
                        .getFirst().resource().type());
    }
}

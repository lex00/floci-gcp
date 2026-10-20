package io.floci.gcp.services.gke;

import io.floci.gcp.core.common.GcpException;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;

import java.util.Set;

/**
 * Answers 404 naming the path for a {@code /container/...} request that no GKE route serves.
 * Without it the request matches the Cloud Storage XML routes as an object in a bucket named
 * {@code container}. Paths under a GKE controller's base path that match none of its methods
 * reach that controller's catch-all methods instead, which answer the same 404.
 */
@Provider
@Priority(Priorities.AUTHENTICATION - 50)
@ApplicationScoped
public class GkeUnmatchedPathFilter implements ContainerRequestFilter {

    private static final Set<Class<?>> GKE_ROUTES = Set.of(KubernetesController.class,
            KubernetesProjectController.class);

    @Context
    ResourceInfo resourceInfo;

    @Override
    public void filter(ContainerRequestContext ctx) {
        String path = ctx.getUriInfo().getRequestUri().getRawPath();
        if (path == null || !path.startsWith("/container/")) {
            return;
        }
        if (resourceInfo != null && GKE_ROUTES.contains(resourceInfo.getResourceClass())) {
            return;
        }
        throw unmatched(path, ctx.getHeaderString(GkeApiVersionFilter.VERSION_HEADER));
    }

    /** The 404 for {@code path}, spelled with the API version the client asked for. */
    static GcpException unmatched(String path, String version) {
        String requested = GkeBetaFields.V1BETA1.equals(version)
                ? "/container/" + GkeBetaFields.V1BETA1 + "/" + path.substring("/container/v1/".length())
                : path;
        return GcpException.notFound("The container API has no method at " + requested
                + ". floci-gcp serves the v1 and v1beta1 ClusterManager REST routes.");
    }
}

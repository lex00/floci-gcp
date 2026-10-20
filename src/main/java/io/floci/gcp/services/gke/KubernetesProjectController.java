package io.floci.gcp.services.gke;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

/**
 * GKE (container.googleapis.com) project-scoped REST routes — {@code ListUsableSubnetworks} has
 * no {@code location} path segment, unlike every other GKE method, so it can't live on
 * {@link KubernetesController}'s {@code /projects/{project}/locations/{location}} base path.
 */
@Path("/container/v1/projects/{project}")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class KubernetesProjectController {

    private final GkeService gkeService;

    @Inject
    public KubernetesProjectController(GkeService gkeService) {
        this.gkeService = gkeService;
    }

    @GET
    @Path("/aggregated/usableSubnetworks")
    public Response listUsableSubnetworks(
            @PathParam("project") String project) {

        return Response.ok(gkeService.listUsableSubnetworks(project)).build();
    }

    // ── Unmatched paths ──────────────────────────────────────────────────────

    @GET
    @Path("/{rest: .+}")
    public Response unmatchedGet(@Context UriInfo uri, @HeaderParam(GkeApiVersionFilter.VERSION_HEADER) String version) {
        throw GkeUnmatchedPathFilter.unmatched(uri.getRequestUri().getRawPath(), version);
    }

    @POST
    @Path("/{rest: .+}")
    public Response unmatchedPost(@Context UriInfo uri, @HeaderParam(GkeApiVersionFilter.VERSION_HEADER) String version) {
        throw GkeUnmatchedPathFilter.unmatched(uri.getRequestUri().getRawPath(), version);
    }

    @PUT
    @Path("/{rest: .+}")
    public Response unmatchedPut(@Context UriInfo uri, @HeaderParam(GkeApiVersionFilter.VERSION_HEADER) String version) {
        throw GkeUnmatchedPathFilter.unmatched(uri.getRequestUri().getRawPath(), version);
    }

    @DELETE
    @Path("/{rest: .+}")
    public Response unmatchedDelete(@Context UriInfo uri, @HeaderParam(GkeApiVersionFilter.VERSION_HEADER) String version) {
        throw GkeUnmatchedPathFilter.unmatched(uri.getRequestUri().getRawPath(), version);
    }
}

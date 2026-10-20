package io.floci.gcp.services.cloudasset;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/** Cloud Asset Inventory v1, mounted under {@code /cloudasset} because {@code /v1/projects} belongs to IAM. */
@Path("/cloudasset/v1")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class CloudAssetController {

    private final CloudAssetService service;

    @Inject
    public CloudAssetController(CloudAssetService service) {
        this.service = service;
    }

    @GET
    @Path("/{scopeType}/{scopeId: [^/:]+}:searchAllResources")
    public Response searchAllResources(@PathParam("scopeType") String scopeType, @PathParam("scopeId") String scopeId,
                                       @QueryParam("query") String query,
                                       @QueryParam("assetTypes") List<String> assetTypes,
                                       @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                       @QueryParam("pageToken") String pageToken,
                                       @QueryParam("orderBy") String orderBy,
                                       @QueryParam("readMask") String readMask) {
        return Response.ok(service.searchAllResources(scopeType + "/" + scopeId, query, assetTypes, pageSize,
                pageToken, orderBy, readMask)).build();
    }
}

package io.floci.gcp.services.resourcemanager;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.Encoded;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.Map;

/** Cloud Resource Manager v3 tag keys, tag values, tag bindings and their operations. */
@Path("/v3")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class ResourceManagerTagsController {

    private final TagService tags;

    @Inject
    public ResourceManagerTagsController(TagService tags) {
        this.tags = tags;
    }

    @POST
    @Path("/tagKeys")
    public Response createTagKey(@QueryParam("validateOnly") boolean validateOnly, Map<String, Object> body) {
        return Response.ok(tags.createTagKey(body, validateOnly)).build();
    }

    @GET
    @Path("/tagKeys")
    public Response listTagKeys(@QueryParam("parent") String parent,
                                @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                @QueryParam("pageToken") String pageToken) {
        return Response.ok(tags.listTagKeys(parent, pageSize, pageToken)).build();
    }

    @GET
    @Path("/tagKeys/namespaced")
    public Response getNamespacedTagKey(@QueryParam("name") String name) {
        return Response.ok(tags.getNamespacedTagKey(name)).build();
    }

    @GET
    @Path("/tagKeys/{id}")
    public Response getTagKey(@PathParam("id") String id) {
        return Response.ok(tags.getTagKey(id)).build();
    }

    @PATCH
    @Path("/tagKeys/{id}")
    public Response patchTagKey(@PathParam("id") String id, @QueryParam("updateMask") String updateMask,
                                @QueryParam("validateOnly") boolean validateOnly, Map<String, Object> body) {
        return Response.ok(tags.patchTagKey(id, body, updateMask, validateOnly)).build();
    }

    @DELETE
    @Path("/tagKeys/{id}")
    public Response deleteTagKey(@PathParam("id") String id, @QueryParam("etag") String etag,
                                 @QueryParam("validateOnly") boolean validateOnly) {
        return Response.ok(tags.deleteTagKey(id, etag, validateOnly)).build();
    }

    @POST
    @Path("/tagValues")
    public Response createTagValue(@QueryParam("validateOnly") boolean validateOnly, Map<String, Object> body) {
        return Response.ok(tags.createTagValue(body, validateOnly)).build();
    }

    @GET
    @Path("/tagValues")
    public Response listTagValues(@QueryParam("parent") String parent,
                                  @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                  @QueryParam("pageToken") String pageToken) {
        return Response.ok(tags.listTagValues(parent, pageSize, pageToken)).build();
    }

    @GET
    @Path("/tagValues/namespaced")
    public Response getNamespacedTagValue(@QueryParam("name") String name) {
        return Response.ok(tags.getNamespacedTagValue(name)).build();
    }

    @GET
    @Path("/tagValues/{id}")
    public Response getTagValue(@PathParam("id") String id) {
        return Response.ok(tags.getTagValue(id)).build();
    }

    @PATCH
    @Path("/tagValues/{id}")
    public Response patchTagValue(@PathParam("id") String id, @QueryParam("updateMask") String updateMask,
                                  @QueryParam("validateOnly") boolean validateOnly, Map<String, Object> body) {
        return Response.ok(tags.patchTagValue(id, body, updateMask, validateOnly)).build();
    }

    @DELETE
    @Path("/tagValues/{id}")
    public Response deleteTagValue(@PathParam("id") String id, @QueryParam("etag") String etag,
                                   @QueryParam("validateOnly") boolean validateOnly) {
        return Response.ok(tags.deleteTagValue(id, etag, validateOnly)).build();
    }

    @POST
    @Path("/tagBindings")
    public Response createTagBinding(@QueryParam("validateOnly") boolean validateOnly, Map<String, Object> body) {
        return Response.ok(tags.createTagBinding(body, validateOnly)).build();
    }

    @GET
    @Path("/tagBindings")
    public Response listTagBindings(@QueryParam("parent") String parent,
                                    @QueryParam("pageSize") @DefaultValue("0") int pageSize,
                                    @QueryParam("pageToken") String pageToken) {
        return Response.ok(tags.listTagBindings(parent, pageSize, pageToken)).build();
    }

    @DELETE
    @Path("/tagBindings/{rest: .+}")
    public Response deleteTagBinding(@Encoded @PathParam("rest") String rest) {
        return Response.ok(tags.deleteTagBinding(rest)).build();
    }

    @GET
    @Path("/operations/{id: .+}")
    public Response getOperation(@PathParam("id") String id) {
        return Response.ok(tags.getOperation(id)).build();
    }
}

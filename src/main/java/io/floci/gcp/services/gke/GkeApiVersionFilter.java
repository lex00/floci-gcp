package io.floci.gcp.services.gke;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.PreMatching;
import jakarta.ws.rs.ext.Provider;

import java.net.URI;

/**
 * Serves the container API under {@code v1beta1} through the {@code v1} routes: runs after
 * {@code ServiceRoutingFilter} has mapped host-mode requests onto {@code /container/...}, rewrites
 * {@code /container/v1beta1/...} to {@code /container/v1/...} and records the requested version
 * in {@link #VERSION_HEADER} for {@link GkeBetaFields}. The header is always set by this filter,
 * never taken from the client.
 */
@Provider
@PreMatching
@Priority(Priorities.AUTHENTICATION - 99)
@ApplicationScoped
public class GkeApiVersionFilter implements ContainerRequestFilter {

    static final String VERSION_HEADER = "X-Floci-Gcp-Container-Api-Version";

    private static final String BETA_PREFIX = "/container/" + GkeBetaFields.V1BETA1 + "/";

    @Override
    public void filter(ContainerRequestContext ctx) {
        ctx.getHeaders().remove(VERSION_HEADER);
        URI original = ctx.getUriInfo().getRequestUri();
        String path = original.getRawPath();
        if (path == null || !path.startsWith(BETA_PREFIX)) {
            return;
        }
        ctx.getHeaders().putSingle(VERSION_HEADER, GkeBetaFields.V1BETA1);
        String query = original.getRawQuery() == null ? "" : "?" + original.getRawQuery();
        ctx.setRequestUri(URI.create(original.getScheme() + "://" + original.getRawAuthority()
                + "/container/v1/" + path.substring(BETA_PREFIX.length()) + query));
    }
}

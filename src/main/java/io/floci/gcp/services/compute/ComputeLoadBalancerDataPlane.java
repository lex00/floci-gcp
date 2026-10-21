package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.services.cloudrun.CloudRunService;
import io.floci.gcp.services.cloudrun.model.CloudRunRuntimeInstance;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.vertx.http.HttpServerStart;
import io.vertx.core.MultiMap;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.core.net.HostAndPort;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.event.ObservesAsync;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Serves global external Application Load Balancers. Each single-port forwarding rule's port is
 * opened in the emulator, and a request on it follows the target HTTP proxy, URL map and backend
 * service to the Cloud Run service of a serverless NEG.
 */
@ApplicationScoped
public class ComputeLoadBalancerDataPlane {
    private static final Logger LOG = Logger.getLogger(ComputeLoadBalancerDataPlane.class);
    private static final Set<String> HOP_BY_HOP_HEADERS = Set.of("connection", "keep-alive", "proxy-authenticate",
            "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade", "host", "content-length", "expect");

    private final ComputeService compute;
    private final CloudRunService cloudRun;
    private final EmulatorConfig config;
    private final Vertx vertx;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
    private final Map<Integer, HttpServer> listeners = new HashMap<>();

    record Reply(int status, Map<String, List<String>> headers, byte[] body) {
        static Reply text(int status, String message) {
            return new Reply(status, Map.of("content-type", List.of("text/plain; charset=utf-8")),
                    (message + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    @Inject
    public ComputeLoadBalancerDataPlane(ComputeService compute, CloudRunService cloudRun, EmulatorConfig config, Vertx vertx) {
        this.compute = compute;
        this.cloudRun = cloudRun;
        this.config = config;
        this.vertx = vertx;
    }

    void start(@ObservesAsync HttpServerStart event) {
        reconcile();
    }

    // Runs off the caller's thread: the event fires while ComputeService holds its lock.
    void forwardingRulesChanged(@Observes ComputeService.ForwardingRulesChanged event) {
        vertx.executeBlocking(() -> {
            reconcile();
            return null;
        }, false).onFailure(e -> LOG.warnf(e, "Reconciling load balancer listeners failed"));
    }

    synchronized void stop(@Observes ShutdownEvent event) {
        listeners.values().forEach(HttpServer::close);
        listeners.clear();
    }

    synchronized void reconcile() {
        if (!config.services().compute().enabled()) {
            return;
        }
        Set<Integer> ports = new TreeSet<>();
        for (ObjectNode rule : compute.globalForwardingRules()) {
            int port = listenerPort(rule);
            if (port == config.port()) {
                LOG.warnf("Forwarding rule %s shares the emulator port %d and is not served", rule.path("name").asText(), port);
            } else if (port > 0) {
                ports.add(port);
            }
        }
        listeners.entrySet().removeIf(entry -> {
            if (ports.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().close();
            LOG.infof("Load balancer listener on port %d closed", entry.getKey());
            return true;
        });
        for (int port : ports) {
            if (listeners.containsKey(port)) {
                continue;
            }
            HttpServer server = vertx.createHttpServer().requestHandler(request -> handle(port, request));
            listeners.put(port, server);
            server.listen(port, "0.0.0.0")
                    .onSuccess(s -> LOG.infof("Load balancer listener on port %d opened", port))
                    .onFailure(e -> {
                        LOG.warnf("Load balancer listener on port %d failed to open: %s", port, e.getMessage());
                        synchronized (this) {
                            listeners.remove(port, server);
                        }
                    });
        }
    }

    synchronized boolean listening(int port) {
        return listeners.containsKey(port);
    }

    /** The rule's port when its portRange names one port, else 0. */
    static int listenerPort(ObjectNode rule) {
        String range = rule.path("portRange").asText();
        String[] bounds = range.split("-");
        if (bounds.length == 2 && !bounds[0].equals(bounds[1])) {
            return 0;
        }
        try {
            return Integer.parseInt(bounds[0]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private void handle(int port, HttpServerRequest request) {
        HostAndPort authority = request.authority();
        String host = authority == null ? null : authority.port() < 0 ? authority.host() : authority.host() + ":" + authority.port();
        String method = request.method().name();
        String uri = request.uri();
        MultiMap headers = request.headers();
        String client = request.remoteAddress() == null ? null : request.remoteAddress().host();
        request.body()
                .compose(body -> vertx.executeBlocking(() -> route(port, host, method, uri, headers, body.getBytes(), client), false))
                .onSuccess(reply -> send(request, reply))
                .onFailure(e -> {
                    LOG.warnf(e, "Load balancer request on port %d failed", port);
                    send(request, Reply.text(502, "Load balancer request failed: " + e.getMessage()));
                });
    }

    Reply route(int port, String host, String method, String uri, MultiMap headers, byte[] body, String clientIp) {
        String hostName = hostName(host);
        List<ObjectNode> rules = compute.globalForwardingRules().stream()
                .filter(r -> listenerPort(r) == port)
                .sorted(Comparator.comparing((ObjectNode r) -> r.path("creationTimestamp").asText()))
                .toList();
        if (rules.isEmpty()) {
            return Reply.text(404, "No forwarding rule listens on port " + port);
        }
        ObjectNode rule = rules.stream().filter(r -> r.path("IPAddress").asText().equals(hostName)).findFirst().orElse(rules.getFirst());
        String project = project(rule.path("selfLink").asText());
        Map<String, ObjectNode> resources = compute.resources(project);
        ObjectNode proxy = resolve(resources, project, rule.path("target").asText());
        ObjectNode urlMap = proxy == null ? null : resolve(resources, project, proxy.path("urlMap").asText());
        if (urlMap == null) {
            return Reply.text(502, "Forwarding rule " + rule.path("name").asText() + " has no URL map");
        }
        int query = uri.indexOf('?');
        String path = query < 0 ? uri : uri.substring(0, query);
        ObjectNode backendService = resolve(resources, project, service(urlMap, hostName, path));
        if (backendService == null) {
            return Reply.text(502, "URL map " + urlMap.path("name").asText() + " routes to no backend service");
        }
        for (JsonNode backend : backendService.path("backends")) {
            ObjectNode group = resolve(resources, project, backend.path("group").asText());
            if (group == null || !group.path("networkEndpointType").asText().equals("SERVERLESS")
                    || !group.path("cloudRun").hasNonNull("service")) {
                continue;
            }
            String region = group.path("region").asText();
            String serviceName = "projects/" + project + "/locations/" + region.substring(region.lastIndexOf('/') + 1)
                    + "/services/" + group.path("cloudRun").path("service").asText();
            Optional<CloudRunRuntimeInstance> runtime = readyRuntime(serviceName);
            if (runtime.isPresent()) {
                return forward(runtime.get(), method, uri, headers, body, host, clientIp);
            }
        }
        return Reply.text(503, "Backend service " + backendService.path("name").asText() + " has no ready Cloud Run backend");
    }

    private Optional<CloudRunRuntimeInstance> readyRuntime(String serviceName) {
        try {
            return cloudRun.readyRuntime(serviceName);
        } catch (GcpException e) {
            LOG.debugf("Serverless NEG names an unavailable Cloud Run service %s: %s", serviceName, e.getMessage());
            return Optional.empty();
        }
    }

    private Reply forward(CloudRunRuntimeInstance runtime, String method, String uri, MultiMap headers, byte[] body,
                          String host, String clientIp) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(runtime.endpointUri(uri)))
                .version(runtime.ingressH2c() ? HttpClient.Version.HTTP_2 : HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofMillis(runtime.requestTimeoutMillis()))
                .method(method, body.length == 0 ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        String forwardedFor = null;
        for (Map.Entry<String, String> header : headers) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            if (name.equals("x-forwarded-for")) {
                forwardedFor = header.getValue();
            } else if (!HOP_BY_HOP_HEADERS.contains(name)) {
                builder.header(header.getKey(), header.getValue());
            }
        }
        if (clientIp != null) {
            builder.header("X-Forwarded-For", forwardedFor == null ? clientIp : forwardedFor + ", " + clientIp);
        }
        builder.header("X-Forwarded-Proto", "http");
        if (host != null) {
            builder.header("X-Forwarded-Host", host);
        }
        builder.header("Via", "1.1 google");
        try {
            HttpResponse<byte[]> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Reply(response.statusCode(), response.headers().map(), response.body());
        } catch (HttpTimeoutException e) {
            return Reply.text(504, "Cloud Run backend timed out: " + runtime.serviceName());
        } catch (IOException e) {
            return Reply.text(502, "Cloud Run backend connection failed: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Reply.text(503, "Load balancer request interrupted");
        }
    }

    private static void send(HttpServerRequest request, Reply reply) {
        HttpServerResponse response = request.response().setStatusCode(reply.status());
        reply.headers().forEach((name, values) -> {
            if (!name.startsWith(":") && !HOP_BY_HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                response.headers().add(name, values);
            }
        });
        response.end(Buffer.buffer(reply.body()));
    }

    /** The backend service link the URL map picks for a host and path. */
    static String service(JsonNode urlMap, String host, String path) {
        String matcherName = pathMatcher(urlMap.path("hostRules"), host);
        JsonNode matcher = null;
        for (JsonNode candidate : urlMap.path("pathMatchers")) {
            if (candidate.path("name").asText().equals(matcherName)) {
                matcher = candidate;
            }
        }
        if (matcher == null) {
            return urlMap.path("defaultService").asText();
        }
        String best = null;
        int bestLength = -1;
        for (JsonNode rule : matcher.path("pathRules")) {
            for (JsonNode pattern : rule.path("paths")) {
                String value = pattern.asText();
                boolean matches = value.endsWith("/*") ? path.startsWith(value.substring(0, value.length() - 1)) : path.equals(value);
                if (matches && value.length() > bestLength) {
                    best = rule.path("service").asText();
                    bestLength = value.length();
                }
            }
        }
        return best != null ? best : matcher.path("defaultService").asText();
    }

    private static String pathMatcher(JsonNode hostRules, String host) {
        String wildcard = null, any = null;
        int wildcardLength = -1;
        for (JsonNode rule : hostRules) {
            for (JsonNode pattern : rule.path("hosts")) {
                String value = pattern.asText().toLowerCase(Locale.ROOT);
                if (value.equals(host)) {
                    return rule.path("pathMatcher").asText();
                }
                if (value.equals("*")) {
                    any = rule.path("pathMatcher").asText();
                } else if (value.startsWith("*") && host.endsWith(value.substring(1)) && value.length() > wildcardLength) {
                    wildcard = rule.path("pathMatcher").asText();
                    wildcardLength = value.length();
                }
            }
        }
        return wildcard != null ? wildcard : any;
    }

    static String hostName(String host) {
        if (host == null) {
            return "";
        }
        String value = host.toLowerCase(Locale.ROOT);
        if (value.startsWith("[")) {
            int end = value.indexOf(']');
            return end < 0 ? value : value.substring(1, end);
        }
        int colon = value.indexOf(':');
        return colon < 0 ? value : value.substring(0, colon);
    }

    private static String project(String selfLink) {
        String rest = selfLink.substring(selfLink.indexOf("projects/") + "projects/".length());
        return rest.substring(0, rest.indexOf('/'));
    }

    private static ObjectNode resolve(Map<String, ObjectNode> resources, String project, String link) {
        if (link == null || link.isBlank()) {
            return null;
        }
        try {
            return resources.get(ComputeService.localPath(project, link));
        } catch (GcpException e) {
            return null;
        }
    }
}

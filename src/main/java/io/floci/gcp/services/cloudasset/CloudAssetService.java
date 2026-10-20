package io.floci.gcp.services.cloudasset;

import io.floci.gcp.config.EmulatorConfig;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.ProjectRecord;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.core.common.ServiceDescriptor;
import io.floci.gcp.core.common.ServiceProtocol;
import io.floci.gcp.core.common.ServiceRegistry;
import io.floci.gcp.services.resourcemanager.TagService;
import io.floci.gcp.services.resourcemanager.model.TagBindingRecord;
import io.floci.gcp.services.resourcemanager.model.TagValueRecord;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Cloud Asset Inventory {@code searchAllResources} over the resources each {@link AssetSource} holds, joined
 * with the Resource Manager tag bindings attached to them. A configurable lag holds each new, changed or
 * deleted resource at its previous answer, counted from the first search that sees the change.
 */
@ApplicationScoped
public class CloudAssetService {

    private static final int DEFAULT_PAGE_SIZE = 100;
    private static final int MAX_PAGE_SIZE = 500;
    private static final Pattern PROJECT_SCOPE = Pattern.compile("projects/([^/]+)");

    private final Instance<AssetSource> sources;
    private final TagService tags;
    private final ProjectRegistry projects;
    private final ServiceRegistry serviceRegistry;
    private final EmulatorConfig config;
    private final long lagMillis;
    private final Map<String, PageState> pageTokens = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Deque<Observation>>> history = new HashMap<>();

    @Inject
    public CloudAssetService(Instance<AssetSource> sources, TagService tags, ProjectRegistry projects,
                             ServiceRegistry serviceRegistry, EmulatorConfig config) {
        this.sources = sources;
        this.tags = tags;
        this.projects = projects;
        this.serviceRegistry = serviceRegistry;
        this.config = config;
        this.lagMillis = config.services().cloudasset().lagSeconds() * 1000L;
    }

    void onStart(@Observes StartupEvent ev) {
        serviceRegistry.register(ServiceDescriptor.builder("cloudasset")
                .enabled(config.services().cloudasset().enabled())
                .protocol(ServiceProtocol.REST)
                .hostToken("cloudasset")
                .pathPrefix("/cloudasset")
                .resourceClasses(CloudAssetController.class)
                .api("cloudasset.googleapis.com", "Cloud Asset API")
                .build());
    }

    public Map<String, Object> searchAllResources(String scope, String query, List<String> assetTypes, int pageSize,
                                                  String pageToken, String orderBy, String readMask) {
        ProjectRecord project = requireProjectScope(scope);
        AssetQuery parsed = AssetQuery.parse(query);
        List<Pattern> types = assetTypePatterns(assetTypes);
        Set<String> mask = readMask(readMask);
        Comparator<SearchResult> order = order(orderBy);
        String paramsKey = String.join("\u0000", scope, Objects.toString(query, ""), String.valueOf(assetTypes),
                String.valueOf(pageSize), Objects.toString(orderBy, ""), Objects.toString(readMask, ""));
        int offset = 0;
        if (pageToken != null && !pageToken.isEmpty()) {
            PageState state = pageTokens.remove(pageToken);
            if (state == null || !state.paramsKey().equals(paramsKey)) {
                throw GcpException.invalidArgument("Invalid page token: " + pageToken);
            }
            offset = state.offset();
        }

        List<SearchResult> matching = visible(project).stream()
                .filter(result -> types.isEmpty() || types.stream().anyMatch(t -> t.matcher(result.asset().assetType()).matches()))
                .filter(parsed::matches)
                .sorted(order)
                .toList();
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        int end = Math.min(matching.size(), offset + size);
        Map<String, Object> response = new LinkedHashMap<>();
        if (offset < end) {
            response.put("results", matching.subList(offset, end).stream().map(r -> r.toJson(mask)).toList());
        }
        if (end < matching.size()) {
            String next = UUID.randomUUID().toString();
            pageTokens.put(next, new PageState(paramsKey, end));
            response.put("nextPageToken", next);
        }
        return response;
    }

    private ProjectRecord requireProjectScope(String scope) {
        if (scope != null && (scope.startsWith("folders/") || scope.startsWith("organizations/"))) {
            throw GcpException.unimplemented("Searching a folder or organization scope is not supported by floci-gcp.");
        }
        if (scope == null || !PROJECT_SCOPE.matcher(scope).matches()) {
            throw GcpException.invalidArgument("Invalid scope: " + scope);
        }
        return projects.require(scope.substring("projects/".length()));
    }

    private List<SearchResult> current(ProjectRecord project) {
        List<SearchResult> results = new ArrayList<>();
        for (AssetSource source : sources) {
            for (Asset asset : source.assets(project.getProjectId())) {
                results.add(withTags(asset, project.getProjectNumber()));
            }
        }
        return results;
    }

    private SearchResult withTags(Asset asset, String projectNumber) {
        Set<String> valueNames = new LinkedHashSet<>();
        for (String parent : asset.tagParents()) {
            for (TagBindingRecord binding : tags.bindingsFor(parent)) {
                valueNames.add(binding.getTagValue());
            }
        }
        List<String> tagKeys = new ArrayList<>(), tagValues = new ArrayList<>(), tagValueIds = new ArrayList<>();
        List<Map<String, String>> attached = new ArrayList<>();
        for (String valueName : valueNames) {
            tags.findValue(valueName).ifPresent(value -> {
                String keyNamespaced = value.getNamespacedName().substring(0, value.getNamespacedName().lastIndexOf('/'));
                tagKeys.add(keyNamespaced);
                tagValues.add(value.getNamespacedName());
                tagValueIds.add(value.getName());
                attached.add(tag(value, keyNamespaced));
            });
        }
        return new SearchResult(asset, projectNumber, tagKeys, tagValues, tagValueIds, attached);
    }

    private static Map<String, String> tag(TagValueRecord value, String keyNamespaced) {
        Map<String, String> tag = new LinkedHashMap<>();
        tag.put("tagKey", keyNamespaced);
        tag.put("tagKeyId", value.getParent());
        tag.put("tagValue", value.getNamespacedName());
        tag.put("tagValueId", value.getName());
        return tag;
    }

    /** The current results, or with a lag the latest answer for each resource that is at least the lag old. */
    private synchronized List<SearchResult> visible(ProjectRecord project) {
        List<SearchResult> current = current(project);
        if (lagMillis <= 0) {
            return current;
        }
        long now = System.currentTimeMillis();
        Map<String, Deque<Observation>> seen = history.computeIfAbsent(project.getProjectId(), p -> new HashMap<>());
        Map<String, SearchResult> byName = new HashMap<>();
        current.forEach(result -> byName.put(result.asset().name(), result));
        byName.forEach((name, result) -> {
            Deque<Observation> observations = seen.computeIfAbsent(name, n -> new ArrayDeque<>());
            if (observations.isEmpty() || !Objects.equals(observations.peekLast().result(), result)) {
                observations.addLast(new Observation(now, result));
            }
        });
        seen.forEach((name, observations) -> {
            if (!byName.containsKey(name) && observations.peekLast().result() != null) {
                observations.addLast(new Observation(now, null));
            }
        });
        List<SearchResult> visible = new ArrayList<>();
        seen.values().forEach(observations -> {
            Observation answer = null;
            for (Observation observation : observations) {
                if (observation.seenAt() <= now - lagMillis) {
                    answer = observation;
                }
            }
            while (observations.size() > 1 && observations.peekFirst() != answer && answer != null) {
                observations.removeFirst();
            }
            if (answer != null && answer.result() != null) {
                visible.add(answer.result());
            }
        });
        return visible;
    }

    private static List<Pattern> assetTypePatterns(List<String> assetTypes) {
        if (assetTypes == null) {
            return List.of();
        }
        List<Pattern> patterns = new ArrayList<>();
        for (String assetType : assetTypes) {
            for (String part : assetType.split(",")) {
                if (part.isBlank()) {
                    continue;
                }
                try {
                    patterns.add(Pattern.compile(part.trim()));
                } catch (PatternSyntaxException e) {
                    throw GcpException.invalidArgument("Invalid asset type regular expression: " + part);
                }
            }
        }
        return patterns;
    }

    private static Set<String> readMask(String readMask) {
        if (readMask == null || readMask.isBlank() || readMask.trim().equals("*")) {
            return null;
        }
        Set<String> fields = new LinkedHashSet<>();
        for (String field : readMask.split(",")) {
            String trimmed = field.trim();
            if (!SearchResult.FIELDS.contains(trimmed)) {
                throw GcpException.invalidArgument("Invalid read_mask field: " + trimmed);
            }
            fields.add(trimmed);
        }
        return fields;
    }

    private static Comparator<SearchResult> order(String orderBy) {
        Comparator<SearchResult> order = Comparator.comparing(r -> r.asset().name());
        if (orderBy == null || orderBy.isBlank()) {
            return order;
        }
        List<String> clauses = Arrays.stream(orderBy.split(",")).map(String::trim).filter(c -> !c.isEmpty()).toList();
        Comparator<SearchResult> combined = null;
        for (String clause : clauses) {
            String[] words = clause.split("\\s+");
            String field = words[0];
            boolean descending = words.length == 2 && words[1].equalsIgnoreCase("DESC");
            if (words.length > 2 || words.length == 2 && !descending && !words[1].equalsIgnoreCase("ASC")
                    || !Set.of("name", "assetType", "project", "displayName", "location", "createTime", "updateTime").contains(field)) {
                throw GcpException.invalidArgument("Invalid order_by clause: " + clause);
            }
            Comparator<SearchResult> next = Comparator.comparing(r -> r.sortValue(field),
                    Comparator.nullsLast(Comparator.<String>naturalOrder()));
            if (descending) {
                next = next.reversed();
            }
            combined = combined == null ? next : combined.thenComparing(next);
        }
        return combined.thenComparing(order);
    }

    private record PageState(String paramsKey, int offset) {}

    private record Observation(long seenAt, SearchResult result) {}
}

package io.floci.gcp.services.resourcemanager;

import com.fasterxml.jackson.core.type.TypeReference;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.common.PageToken;
import io.floci.gcp.core.common.ProjectRecord;
import io.floci.gcp.core.common.ProjectRegistry;
import io.floci.gcp.core.storage.StorageBackend;
import io.floci.gcp.core.storage.StorageFactory;
import io.floci.gcp.services.compute.ComputeService;
import io.floci.gcp.services.gcs.GcsService;
import io.floci.gcp.services.resourcemanager.model.TagBindingRecord;
import io.floci.gcp.services.resourcemanager.model.TagKeyRecord;
import io.floci.gcp.services.resourcemanager.model.TagValueRecord;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cloud Resource Manager v3 tags: tag keys, tag values and tag bindings. Every mutation runs under
 * the service lock, so a value with bindings and a key with values cannot be deleted while a
 * binding or value is being created for them.
 */
@ApplicationScoped
public class TagService {

    private static final Logger LOG = Logger.getLogger(TagService.class);

    private static final String V3_TYPE = "type.googleapis.com/google.cloud.resourcemanager.v3.";
    private static final Pattern SHORT_NAME = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9._-]{0,254}[A-Za-z0-9])?");
    private static final Pattern ORGANIZATION = Pattern.compile("organizations/([0-9]+)");
    private static final String PROJECT_PARENT = "//cloudresourcemanager.googleapis.com/projects/";
    private static final String BUCKET_PARENT = "//storage.googleapis.com/projects/_/buckets/";
    private static final String COMPUTE_PARENT = "//compute.googleapis.com/projects/";
    private static final long FIRST_ID = 281474976710656L;
    private static final int MAX_DESCRIPTION = 256;

    private final StorageBackend<String, TagKeyRecord> keys;
    private final StorageBackend<String, TagValueRecord> values;
    private final StorageBackend<String, TagBindingRecord> bindings;
    private final StorageBackend<String, Map<String, Object>> operations;
    private final ProjectRegistry projects;
    private final GcsService gcs;
    private final ComputeService compute;
    private long nextId;

    @Inject
    public TagService(StorageFactory storageFactory, ProjectRegistry projects, GcsService gcs, ComputeService compute) {
        this.keys = storageFactory.createGlobal("resourcemanager-tag-keys", "resourcemanager-tag-keys.json",
                new TypeReference<Map<String, TagKeyRecord>>() {});
        this.values = storageFactory.createGlobal("resourcemanager-tag-values", "resourcemanager-tag-values.json",
                new TypeReference<Map<String, TagValueRecord>>() {});
        this.bindings = storageFactory.createGlobal("resourcemanager-tag-bindings", "resourcemanager-tag-bindings.json",
                new TypeReference<Map<String, TagBindingRecord>>() {});
        this.operations = storageFactory.createGlobal("resourcemanager-tag-operations",
                "resourcemanager-tag-operations.json", new TypeReference<Map<String, Map<String, Object>>>() {});
        this.projects = projects;
        this.gcs = gcs;
        this.compute = compute;
    }

    // ── Tag keys ──────────────────────────────────────────────────────────────

    public synchronized Map<String, Object> createTagKey(Map<String, Object> body, boolean validateOnly) {
        Map<String, Object> request = requireBody(body);
        String namespace = keyNamespace(requiredString(request, "parent"));
        String shortName = shortName(request);
        String namespacedName = namespace + "/" + shortName;
        if (findKeyByNamespacedName(namespacedName).isPresent()) {
            throw GcpException.alreadyExists("A TagKey with short name " + shortName + " already exists under "
                    + requiredString(request, "parent") + ".");
        }
        String now = Instant.now().toString();
        TagKeyRecord key = new TagKeyRecord();
        key.setName("tagKeys/" + nextId());
        key.setParent(requiredString(request, "parent"));
        key.setShortName(shortName);
        key.setNamespacedName(namespacedName);
        key.setDescription(description(request));
        key.setPurpose(optionalString(request, "purpose"));
        key.setPurposeData(stringMap(request.get("purposeData")));
        key.setEtag(etag());
        key.setCreateTime(now);
        key.setUpdateTime(now);
        if (!validateOnly) {
            keys.put(key.getName(), key);
            LOG.infof("create tag key name=%s namespacedName=%s", key.getName(), namespacedName);
        }
        return operation("tkc", "CreateTagKeyMetadata", "TagKey", toJson(key), !validateOnly);
    }

    public Map<String, Object> getTagKey(String id) {
        return toJson(requireKey("tagKeys/" + id));
    }

    public Map<String, Object> getNamespacedTagKey(String namespacedName) {
        return toJson(findKeyByNamespacedName(canonicalNamespacedName(namespacedName))
                .orElseThrow(() -> GcpException.permissionDenied(ProjectRegistry.PERMISSION_DENIED_MESSAGE)));
    }

    public Map<String, Object> listTagKeys(String parent, int pageSize, String pageToken) {
        if (parent == null || parent.isBlank()) {
            throw GcpException.invalidArgument("parent is required.");
        }
        String namespace = keyNamespace(parent);
        List<Map<String, Object>> matching = keys.scan(k -> true).stream()
                .filter(key -> namespaceOf(key.getNamespacedName()).equals(namespace))
                .sorted(Comparator.comparing(TagKeyRecord::getName))
                .map(TagService::toJson)
                .toList();
        return page("tagKeys", matching, pageSize, pageToken);
    }

    public synchronized Map<String, Object> patchTagKey(String id, Map<String, Object> body, String updateMask,
                                                        boolean validateOnly) {
        TagKeyRecord key = requireKey("tagKeys/" + id);
        Map<String, Object> request = requireBody(body);
        checkEtag(key.getEtag(), optionalString(request, "etag"));
        String description = patchedDescription(request, updateMask, key.getDescription());
        if (!validateOnly) {
            key.setDescription(description);
            key.setEtag(etag());
            key.setUpdateTime(Instant.now().toString());
            keys.put(key.getName(), key);
        }
        return operation("tku", "UpdateTagKeyMetadata", "TagKey", toJson(key), !validateOnly);
    }

    public synchronized Map<String, Object> deleteTagKey(String id, String etag, boolean validateOnly) {
        TagKeyRecord key = requireKey("tagKeys/" + id);
        checkEtag(key.getEtag(), etag);
        if (values.scan(k -> true).stream().anyMatch(v -> v.getParent().equals(key.getName()))) {
            throw GcpException.failedPrecondition("TagKey " + key.getName() + " has TagValues and cannot be deleted.");
        }
        if (!validateOnly) {
            keys.delete(key.getName());
            LOG.infof("delete tag key name=%s", key.getName());
        }
        return operation("tkd", "DeleteTagKeyMetadata", "TagKey", toJson(key), !validateOnly);
    }

    // ── Tag values ────────────────────────────────────────────────────────────

    public synchronized Map<String, Object> createTagValue(Map<String, Object> body, boolean validateOnly) {
        Map<String, Object> request = requireBody(body);
        TagKeyRecord key = resolveKey(requiredString(request, "parent"));
        String shortName = shortName(request);
        String namespacedName = key.getNamespacedName() + "/" + shortName;
        if (findValueByNamespacedName(namespacedName).isPresent()) {
            throw GcpException.alreadyExists("A TagValue with short name " + shortName + " already exists under "
                    + key.getName() + ".");
        }
        String now = Instant.now().toString();
        TagValueRecord value = new TagValueRecord();
        value.setName("tagValues/" + nextId());
        value.setParent(key.getName());
        value.setShortName(shortName);
        value.setNamespacedName(namespacedName);
        value.setDescription(description(request));
        value.setEtag(etag());
        value.setCreateTime(now);
        value.setUpdateTime(now);
        if (!validateOnly) {
            values.put(value.getName(), value);
            LOG.infof("create tag value name=%s namespacedName=%s", value.getName(), namespacedName);
        }
        return operation("tvc", "CreateTagValueMetadata", "TagValue", toJson(value), !validateOnly);
    }

    public Map<String, Object> getTagValue(String id) {
        return toJson(requireValue("tagValues/" + id));
    }

    public Map<String, Object> getNamespacedTagValue(String namespacedName) {
        return toJson(findValueByNamespacedName(canonicalNamespacedName(namespacedName))
                .orElseThrow(() -> GcpException.permissionDenied(ProjectRegistry.PERMISSION_DENIED_MESSAGE)));
    }

    public Map<String, Object> listTagValues(String parent, int pageSize, String pageToken) {
        if (parent == null || parent.isBlank()) {
            throw GcpException.invalidArgument("parent is required.");
        }
        String keyName = resolveKey(parent).getName();
        List<Map<String, Object>> matching = values.scan(k -> true).stream()
                .filter(value -> value.getParent().equals(keyName))
                .sorted(Comparator.comparing(TagValueRecord::getName))
                .map(TagService::toJson)
                .toList();
        return page("tagValues", matching, pageSize, pageToken);
    }

    public synchronized Map<String, Object> patchTagValue(String id, Map<String, Object> body, String updateMask,
                                                          boolean validateOnly) {
        TagValueRecord value = requireValue("tagValues/" + id);
        Map<String, Object> request = requireBody(body);
        checkEtag(value.getEtag(), optionalString(request, "etag"));
        String description = patchedDescription(request, updateMask, value.getDescription());
        if (!validateOnly) {
            value.setDescription(description);
            value.setEtag(etag());
            value.setUpdateTime(Instant.now().toString());
            values.put(value.getName(), value);
        }
        return operation("tvu", "UpdateTagValueMetadata", "TagValue", toJson(value), !validateOnly);
    }

    public synchronized Map<String, Object> deleteTagValue(String id, String etag, boolean validateOnly) {
        TagValueRecord value = requireValue("tagValues/" + id);
        checkEtag(value.getEtag(), etag);
        if (bindings.scan(k -> true).stream().anyMatch(b -> b.getTagValue().equals(value.getName()))) {
            throw GcpException.failedPrecondition("TagValue " + value.getName()
                    + " has TagBindings and cannot be deleted.");
        }
        if (!validateOnly) {
            values.delete(value.getName());
            LOG.infof("delete tag value name=%s", value.getName());
        }
        return operation("tvd", "DeleteTagValueMetadata", "TagValue", toJson(value), !validateOnly);
    }

    // ── Tag bindings ──────────────────────────────────────────────────────────

    public synchronized Map<String, Object> createTagBinding(Map<String, Object> body, boolean validateOnly) {
        Map<String, Object> request = requireBody(body);
        String parent = requiredString(request, "parent");
        String tagValue = optionalString(request, "tagValue");
        String tagValueNamespacedName = optionalString(request, "tagValueNamespacedName");
        if ((tagValue == null) == (tagValueNamespacedName == null)) {
            throw GcpException.invalidArgument("Exactly one of tagValue or tagValueNamespacedName must be set.");
        }
        TagValueRecord value = tagValue != null ? requireValue(tagValue)
                : findValueByNamespacedName(canonicalNamespacedName(tagValueNamespacedName))
                        .orElseThrow(() -> GcpException.permissionDenied(ProjectRegistry.PERMISSION_DENIED_MESSAGE));
        requireBindingParent(parent);
        TagBindingRecord binding = new TagBindingRecord();
        binding.setName(bindingName(parent, value.getName()));
        binding.setParent(parent);
        binding.setTagValue(value.getName());
        binding.setTagValueNamespacedName(value.getNamespacedName());
        if (bindings.get(binding.getName()).isPresent()) {
            throw GcpException.alreadyExists("TagBinding " + binding.getName() + " already exists.");
        }
        if (!validateOnly) {
            bindings.put(binding.getName(), binding);
            LOG.infof("create tag binding parent=%s tagValue=%s", parent, value.getName());
        }
        return operation("tbc", "CreateTagBindingMetadata", "TagBinding", toJson(binding), !validateOnly);
    }

    public Map<String, Object> listTagBindings(String parent, int pageSize, String pageToken) {
        if (parent == null || parent.isBlank()) {
            throw GcpException.invalidArgument("parent is required.");
        }
        List<Map<String, Object>> matching = bindingsFor(parent).stream().map(TagService::toJson).toList();
        return page("tagBindings", matching, pageSize, pageToken);
    }

    /**
     * {@code rest} is the raw path after {@code tagBindings/}. The provider sends the parent encoded once
     * ({@code %2F}) and the Java client encodes the name again ({@code %252F}); a full resource name never
     * contains {@code %}, so decoding until none is left reaches the parent either way.
     */
    public synchronized Map<String, Object> deleteTagBinding(String rest) {
        int split = rest.lastIndexOf("/tagValues/");
        if (split < 0) {
            throw GcpException.invalidArgument("Invalid TagBinding name: tagBindings/" + rest);
        }
        String parent = rest.substring(0, split);
        for (int pass = 0; pass < 3 && parent.contains("%"); pass++) {
            parent = URLDecoder.decode(parent, StandardCharsets.UTF_8);
        }
        String name = bindingName(parent, rest.substring(split + 1));
        if (bindings.get(name).isEmpty()) {
            throw GcpException.notFound("TagBinding " + name + " not found.");
        }
        bindings.delete(name);
        LOG.infof("delete tag binding name=%s", name);
        return operation("tbd", "DeleteTagBindingMetadata", null, null, true);
    }

    /** Bindings on a resource, matching its project by ID or number. */
    public List<TagBindingRecord> bindingsFor(String parent) {
        String canonical = canonicalParent(parent);
        return bindings.scan(k -> true).stream()
                .filter(binding -> canonicalParent(binding.getParent()).equals(canonical))
                .sorted(Comparator.comparing(TagBindingRecord::getName))
                .toList();
    }

    public Optional<TagValueRecord> findValue(String name) {
        return values.get(name);
    }

    public Map<String, Object> getOperation(String id) {
        return operations.get("operations/" + id)
                .orElseThrow(() -> GcpException.notFound("Operation operations/" + id + " not found."));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private String keyNamespace(String parent) {
        Matcher organization = ORGANIZATION.matcher(parent);
        if (organization.matches()) {
            return organization.group(1);
        }
        if (parent.startsWith("projects/") && parent.indexOf('/', 9) < 0 && parent.length() > 9) {
            return projects.require(parent.substring(9)).getProjectId();
        }
        throw GcpException.invalidArgument("Invalid parent " + parent
                + ". It must be organizations/{org_id}, projects/{project_id} or projects/{project_number}.");
    }

    String canonicalNamespacedName(String namespacedName) {
        if (namespacedName == null || namespacedName.isBlank() || namespacedName.indexOf('/') < 0) {
            throw GcpException.invalidArgument("Invalid namespaced name: " + namespacedName);
        }
        String head = namespacedName.substring(0, namespacedName.indexOf('/'));
        if (!ProjectRegistry.isNumber(head)) {
            return namespacedName;
        }
        return projects.find(head).map(ProjectRecord::getProjectId)
                .map(id -> id + namespacedName.substring(head.length()))
                .orElse(namespacedName);
    }

    private TagKeyRecord resolveKey(String keyName) {
        if (keyName.startsWith("tagKeys/")) {
            return requireKey(keyName);
        }
        return findKeyByNamespacedName(canonicalNamespacedName(keyName))
                .orElseThrow(() -> GcpException.permissionDenied(ProjectRegistry.PERMISSION_DENIED_MESSAGE));
    }

    private TagKeyRecord requireKey(String name) {
        return keys.get(name).orElseThrow(() -> GcpException.permissionDenied(ProjectRegistry.PERMISSION_DENIED_MESSAGE));
    }

    private TagValueRecord requireValue(String name) {
        return values.get(name).orElseThrow(() -> GcpException.permissionDenied(ProjectRegistry.PERMISSION_DENIED_MESSAGE));
    }

    private Optional<TagKeyRecord> findKeyByNamespacedName(String namespacedName) {
        return keys.scan(k -> true).stream().filter(k -> k.getNamespacedName().equals(namespacedName)).findFirst();
    }

    private Optional<TagValueRecord> findValueByNamespacedName(String namespacedName) {
        return values.scan(k -> true).stream().filter(v -> v.getNamespacedName().equals(namespacedName)).findFirst();
    }

    private void requireBindingParent(String parent) {
        if (parent.startsWith(PROJECT_PARENT) && parent.indexOf('/', PROJECT_PARENT.length()) < 0) {
            projects.require(parent.substring(PROJECT_PARENT.length()));
        } else if (parent.startsWith(BUCKET_PARENT) && parent.indexOf('/', BUCKET_PARENT.length()) < 0) {
            gcs.getBucket(parent.substring(BUCKET_PARENT.length()));
        } else if (parent.startsWith(COMPUTE_PARENT) && parent.indexOf('/', COMPUTE_PARENT.length()) > 0) {
            String rest = parent.substring(COMPUTE_PARENT.length());
            String project = projects.require(rest.substring(0, rest.indexOf('/'))).getProjectId();
            compute.get(project, rest.substring(rest.indexOf('/') + 1), Map.of());
        } else {
            throw GcpException.unimplemented("Tag bindings on " + parent + " are not supported by floci-gcp. "
                    + "Supported parents are projects, Cloud Storage buckets and Compute Engine resources.");
        }
    }

    /** Rewrites the project segment of a project or Compute parent to the project number. */
    private String canonicalParent(String parent) {
        for (String prefix : List.of(PROJECT_PARENT, COMPUTE_PARENT)) {
            if (parent.startsWith(prefix)) {
                String rest = parent.substring(prefix.length());
                int slash = rest.indexOf('/');
                String project = slash < 0 ? rest : rest.substring(0, slash);
                String number = projects.find(project).map(ProjectRecord::getProjectNumber).orElse(project);
                return prefix + number + (slash < 0 ? "" : rest.substring(slash));
            }
        }
        return parent;
    }

    private static String bindingName(String parent, String tagValue) {
        return "tagBindings/" + URLEncoder.encode(parent, StandardCharsets.UTF_8) + "/" + tagValue;
    }

    private static String namespaceOf(String namespacedName) {
        return namespacedName.substring(0, namespacedName.indexOf('/'));
    }

    private long nextId() {
        if (nextId == 0) {
            nextId = FIRST_ID + 1;
            for (String name : keys.keys()) {
                nextId = Math.max(nextId, idOf(name) + 1);
            }
            for (String name : values.keys()) {
                nextId = Math.max(nextId, idOf(name) + 1);
            }
        }
        return nextId++;
    }

    private static long idOf(String name) {
        return Long.parseLong(name.substring(name.indexOf('/') + 1));
    }

    private Map<String, Object> operation(String kind, String metadataType, String responseType,
                                          Map<String, Object> resource, boolean store) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("@type", V3_TYPE + metadataType);
        Map<String, Object> response = new LinkedHashMap<>();
        if (responseType == null) {
            response.put("@type", "type.googleapis.com/google.protobuf.Empty");
        } else {
            response.put("@type", V3_TYPE + responseType);
            response.putAll(resource);
        }
        Map<String, Object> operation = new LinkedHashMap<>();
        operation.put("name", "operations/" + kind + "." + UUID.randomUUID());
        operation.put("metadata", metadata);
        operation.put("done", true);
        operation.put("response", response);
        if (store) {
            operations.put((String) operation.get("name"), operation);
        }
        return operation;
    }

    private static Map<String, Object> page(String field, List<Map<String, Object>> all, int pageSize,
                                            String pageToken) {
        PageToken.Page<Map<String, Object>> page = PageToken.paginate(all, pageSize, pageToken);
        Map<String, Object> response = new LinkedHashMap<>();
        if (!page.items().isEmpty()) {
            response.put(field, page.items());
        }
        if (page.nextPageToken() != null) {
            response.put("nextPageToken", page.nextPageToken());
        }
        return response;
    }

    private static void checkEtag(String current, String supplied) {
        if (supplied != null && !supplied.isEmpty() && !supplied.equals(current)) {
            throw GcpException.aborted("The etag " + supplied + " does not match the current etag.");
        }
    }

    private static String patchedDescription(Map<String, Object> request, String updateMask, String current) {
        List<String> paths = updateMask == null || updateMask.isBlank() ? List.of("description")
                : Arrays.stream(updateMask.split(",")).map(String::trim).toList();
        for (String path : paths) {
            if (!path.equals("description")) {
                throw GcpException.invalidArgument("Field " + path + " cannot be updated. Only description is mutable.");
            }
        }
        return request.containsKey("description") || updateMask != null && !updateMask.isBlank()
                ? description(request) : current;
    }

    private static String shortName(Map<String, Object> request) {
        String shortName = requiredString(request, "shortName");
        if (!SHORT_NAME.matcher(shortName).matches()) {
            throw GcpException.invalidArgument("Invalid short name " + shortName + ". It must be 1-256 characters, "
                    + "beginning and ending with an alphanumeric character with dashes, underscores, dots, "
                    + "and alphanumerics between.");
        }
        return shortName;
    }

    private static String description(Map<String, Object> request) {
        String description = optionalString(request, "description");
        if (description != null && description.length() > MAX_DESCRIPTION) {
            throw GcpException.invalidArgument("Description must not exceed " + MAX_DESCRIPTION + " characters.");
        }
        return description;
    }

    private static Map<String, Object> requireBody(Map<String, Object> body) {
        if (body == null) {
            throw GcpException.invalidArgument("Request contains an invalid argument.");
        }
        return body;
    }

    private static String requiredString(Map<String, Object> body, String field) {
        String value = optionalString(body, field);
        if (value == null) {
            throw GcpException.invalidArgument(field + " is required.");
        }
        return value;
    }

    private static String optionalString(Map<String, Object> body, String field) {
        Object value = body.get(field);
        return value instanceof String s && !s.isEmpty() ? s : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> stringMap(Object raw) {
        if (raw == null) {
            return null;
        }
        if (!(raw instanceof Map<?, ?>)) {
            throw GcpException.invalidArgument("purposeData must be a map of strings.");
        }
        return new LinkedHashMap<>((Map<String, Object>) raw);
    }

    private static String etag() {
        return UUID.randomUUID().toString();
    }

    static Map<String, Object> toJson(TagKeyRecord key) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", key.getName());
        json.put("parent", key.getParent());
        json.put("shortName", key.getShortName());
        json.put("namespacedName", key.getNamespacedName());
        putIfPresent(json, "description", key.getDescription());
        json.put("createTime", key.getCreateTime());
        json.put("updateTime", key.getUpdateTime());
        json.put("etag", key.getEtag());
        putIfPresent(json, "purpose", key.getPurpose());
        putIfPresent(json, "purposeData", key.getPurposeData());
        return json;
    }

    static Map<String, Object> toJson(TagValueRecord value) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", value.getName());
        json.put("parent", value.getParent());
        json.put("shortName", value.getShortName());
        json.put("namespacedName", value.getNamespacedName());
        putIfPresent(json, "description", value.getDescription());
        json.put("createTime", value.getCreateTime());
        json.put("updateTime", value.getUpdateTime());
        json.put("etag", value.getEtag());
        return json;
    }

    static Map<String, Object> toJson(TagBindingRecord binding) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("name", binding.getName());
        json.put("parent", binding.getParent());
        json.put("tagValue", binding.getTagValue());
        json.put("tagValueNamespacedName", binding.getTagValueNamespacedName());
        return json;
    }

    private static void putIfPresent(Map<String, Object> json, String field, Object value) {
        if (value != null) {
            json.put(field, value);
        }
    }
}

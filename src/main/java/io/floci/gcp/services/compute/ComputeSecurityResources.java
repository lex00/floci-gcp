package io.floci.gcp.services.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.floci.gcp.core.common.GcpException;
import io.floci.gcp.core.tls.CertificateGenerator;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static io.floci.gcp.services.compute.ComputeService.*;

/** SSL certificates and Cloud Armor security policies. */
@ApplicationScoped
public class ComputeSecurityResources implements ComputeResourceHandler {
    static final long DEFAULT_RULE_PRIORITY = 2147483647L;
    private final CertificateGenerator certificates;

    @Inject
    public ComputeSecurityResources(CertificateGenerator certificates) {
        this.certificates = certificates;
    }

    public boolean handles(String kind) { return Set.of("sslCertificates", "securityPolicies").contains(kind); }

    public void create(ComputeService.Context c, ObjectNode r) {
        c.scope("global");
        if (c.collection().equals("sslCertificates")) { certificate(r); return; }
        if (!r.path("type").asText("CLOUD_ARMOR").equals("CLOUD_ARMOR")) { throw GcpException.unimplemented("Only CLOUD_ARMOR security policies are implemented"); }
        r.put("type", "CLOUD_ARMOR");
        rules(r);
        r.put("fingerprint", fingerprint());
    }

    private void certificate(ObjectNode r) {
        String type = r.path("type").asText(r.has("managed") ? "MANAGED" : "SELF_MANAGED");
        r.put("type", type);
        switch (type) {
            case "SELF_MANAGED" -> {
                if (r.has("managed")) { throw GcpException.invalidArgument("SELF_MANAGED certificates take no managed field"); }
                X509Certificate certificate = parse(required(r, "certificate"));
                if (!required(r, "privateKey").contains("PRIVATE KEY-----")) { throw GcpException.invalidArgument("privateKey must be a PEM private key"); }
                r.put("expireTime", certificate.getNotAfter().toInstant().toString());
                ArrayNode names = r.putArray("subjectAlternativeNames");
                alternativeNames(certificate).forEach(names::add);
            }
            case "MANAGED" -> {
                if (r.has("certificate") || r.has("privateKey")) { throw GcpException.invalidArgument("MANAGED certificates take no certificate or privateKey"); }
                ObjectNode managed = objectField(r, "managed");
                List<String> domains = new ArrayList<>();
                for (JsonNode domain : managed.path("domains")) { domains.add(domain.asText()); }
                if (domains.isEmpty() || domains.size() > 100) { throw GcpException.invalidArgument("managed.domains takes 1 to 100 domains"); }
                // The emulator has no ACME exchange: the certificate is issued and ACTIVE at once.
                CertificateGenerator.GeneratedCertificate issued = certificates.generateCertificate(domains);
                X509Certificate certificate = parse(issued.certificatePem());
                r.put("certificate", issued.certificatePem()).put("privateKey", issued.privateKeyPem())
                        .put("expireTime", certificate.getNotAfter().toInstant().toString());
                managed.put("status", "ACTIVE");
                ObjectNode status = managed.putObject("domainStatus");
                ArrayNode names = r.putArray("subjectAlternativeNames");
                domains.forEach(domain -> { status.put(domain, "ACTIVE"); names.add(domain); });
            }
            default -> throw GcpException.invalidArgument("Invalid certificate type: " + type);
        }
    }

    private static X509Certificate parse(String pem) {
        try {
            return (X509Certificate) CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        } catch (CertificateException | ClassCastException e) {
            throw GcpException.invalidArgument("certificate must be a PEM X.509 certificate");
        }
    }

    private static List<String> alternativeNames(X509Certificate certificate) {
        List<String> names = new ArrayList<>();
        try {
            if (certificate.getSubjectAlternativeNames() != null) {
                for (List<?> entry : certificate.getSubjectAlternativeNames()) {
                    if (entry.size() > 1 && entry.get(1) instanceof String name) { names.add(name); }
                }
            }
        } catch (CertificateParsingException e) {
            throw GcpException.invalidArgument("certificate has unreadable subject alternative names");
        }
        return names;
    }

    public void update(ComputeService.Context c, ObjectNode r, ObjectNode body, String verb) {
        if (!c.collection().equals("securityPolicies")) { ComputeResourceHandler.super.update(c, r, body, verb); return; }
        checkFingerprint(r, body, "fingerprint");
        for (Map.Entry<String, JsonNode> e : body.properties()) {
            if (!Set.of("id", "kind", "selfLink", "creationTimestamp", "name", "type", "fingerprint").contains(e.getKey())) { r.set(e.getKey(), e.getValue()); }
        }
        rules(r);
        r.put("fingerprint", fingerprint());
    }

    public void action(ComputeService.Context c, ObjectNode r, String action, ObjectNode body, Map<String, String> query) {
        if (!c.collection().equals("securityPolicies")) { ComputeResourceHandler.super.action(c, r, action, body, query); return; }
        ArrayNode rules = r.withArray("rules");
        switch (action) {
            case "addRule" -> rules.add(body);
            case "patchRule" -> {
                ObjectNode rule = rule(r, query);
                body.properties().forEach(e -> rule.set(e.getKey(), e.getValue()));
            }
            case "removeRule" -> {
                ObjectNode rule = rule(r, query);
                if (rule.path("priority").asLong() == DEFAULT_RULE_PRIORITY) { throw GcpException.invalidArgument("The default rule cannot be removed"); }
                for (int i = 0; i < rules.size(); i++) {
                    if (rules.get(i) == rule) { rules.remove(i); break; }
                }
            }
            default -> ComputeResourceHandler.super.action(c, r, action, body, query);
        }
        rules(r);
        r.put("fingerprint", fingerprint());
    }

    /** The rule {@code ?priority=} names, priority 0 when absent, as {@code getRule} reads it. */
    static ObjectNode rule(ObjectNode policy, Map<String, String> query) {
        long priority;
        try { priority = Long.parseLong(query.getOrDefault("priority", "0")); }
        catch (NumberFormatException e) { throw GcpException.invalidArgument("Invalid priority"); }
        for (JsonNode rule : policy.path("rules")) {
            if (rule.path("priority").asLong() == priority) { return (ObjectNode) rule; }
        }
        throw GcpException.notFound("Security policy rule not found: " + priority);
    }

    private static void rules(ObjectNode r) {
        List<ObjectNode> rules = new ArrayList<>(objectArray(r, "rules"));
        Set<Long> priorities = new HashSet<>();
        for (ObjectNode rule : rules) {
            long priority = rule.path("priority").asLong(0);
            if (priority < 0 || priority > DEFAULT_RULE_PRIORITY || !rule.path("priority").asText("0").matches("[0-9]+")) { throw GcpException.invalidArgument("Invalid rule priority"); }
            if (!priorities.add(priority)) { throw GcpException.invalidArgument("Duplicate rule priority: " + priority); }
            rule.put("priority", priority).put("kind", "compute#securityPolicyRule").put("preview", rule.path("preview").asBoolean(false));
            String action = rule.path("action").asText();
            if (!action.matches("allow|deny\\((403|404|502)\\)")) { throw GcpException.unimplemented("Unsupported security policy rule action: " + action); }
            ObjectNode match = requireObject(rule.path("match"), "match");
            if (match.has("expr")) { throw GcpException.unimplemented("CEL match expressions are not implemented; use srcIpRanges"); }
            if (!match.path("versionedExpr").asText().equals("SRC_IPS_V1")) { throw GcpException.invalidArgument("match.versionedExpr must be SRC_IPS_V1"); }
            JsonNode ranges = match.path("config").path("srcIpRanges");
            if (!ranges.isArray() || ranges.isEmpty() || ranges.size() > 10) { throw GcpException.invalidArgument("match.config.srcIpRanges takes 1 to 10 ranges"); }
            for (JsonNode range : ranges) { range(range.asText()); }
            if (priority == DEFAULT_RULE_PRIORITY && !(ranges.size() == 1 && ranges.get(0).asText().equals("*"))) {
                throw GcpException.invalidArgument("The default rule must match srcIpRanges [\"*\"]");
            }
        }
        if (!priorities.contains(DEFAULT_RULE_PRIORITY)) {
            ObjectNode rule = object().put("description", "default rule").put("priority", DEFAULT_RULE_PRIORITY)
                    .put("action", "allow").put("preview", false).put("kind", "compute#securityPolicyRule");
            rule.putObject("match").put("versionedExpr", "SRC_IPS_V1").putObject("config").putArray("srcIpRanges").add("*");
            rules.add(rule);
        }
        rules.sort(Comparator.comparingLong(rule -> rule.path("priority").asLong()));
        ArrayNode sorted = r.putArray("rules");
        rules.forEach(sorted::add);
    }

    private static void range(String value) {
        if (value.equals("*")) { return; }
        int slash = value.indexOf('/');
        String address = slash < 0 ? value : value.substring(0, slash);
        try {
            int bits = InetAddress.ofLiteral(address).getAddress().length * 8;
            if (slash >= 0) { integer(value.substring(slash + 1), 0, bits, "srcIpRanges prefix length"); }
        } catch (IllegalArgumentException e) {
            throw GcpException.invalidArgument("Invalid srcIpRanges entry: " + value);
        }
    }

    /** Whether a client address falls in one of a rule's srcIpRanges. */
    static boolean matches(JsonNode ranges, String client) {
        byte[] address;
        try { address = InetAddress.ofLiteral(client).getAddress(); }
        catch (IllegalArgumentException | NullPointerException e) { return false; }
        for (JsonNode range : ranges) {
            String value = range.asText();
            if (value.equals("*")) { return true; }
            int slash = value.indexOf('/');
            byte[] network = InetAddress.ofLiteral(slash < 0 ? value : value.substring(0, slash)).getAddress();
            if (network.length != address.length) { continue; }
            int bits = slash < 0 ? network.length * 8 : Integer.parseInt(value.substring(slash + 1));
            boolean inside = true;
            for (int i = 0; i < bits && inside; i++) {
                int mask = 0x80 >> (i % 8);
                inside = (network[i / 8] & mask) == (address[i / 8] & mask);
            }
            if (inside) { return true; }
        }
        return false;
    }
}

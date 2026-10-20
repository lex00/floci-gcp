package io.floci.gcp.services.cloudasset;

import io.floci.gcp.core.common.GcpException;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The subset of the searchAllResources query language floci-gcp evaluates: {@code labels.KEY=VALUE},
 * {@code labels.KEY:VALUE}, {@code labels.KEY:*}, {@code tagKeys}, {@code tagValues} and {@code tagValueIds}
 * with {@code :} or {@code =}, joined with {@code AND}. Any other statement is refused, never matched loosely.
 */
final class AssetQuery {

    private static final Pattern TERM = Pattern.compile(
            "(labels\\.([A-Za-z0-9_\\-\\p{L}]+)|tagKeys|tagValues|tagValueIds)(=|:)(\"[^\"]*\"|[^\\s\"]+)");
    private static final Pattern AND = Pattern.compile("\\s+AND\\s+");

    private final List<Predicate<SearchResult>> terms;

    private AssetQuery(List<Predicate<SearchResult>> terms) {
        this.terms = terms;
    }

    static AssetQuery parse(String query) {
        List<Predicate<SearchResult>> terms = new ArrayList<>();
        if (query == null || query.isBlank()) {
            return new AssetQuery(terms);
        }
        for (String statement : AND.split(query.trim())) {
            Matcher term = TERM.matcher(statement.trim());
            if (!term.matches()) {
                throw GcpException.invalidArgument("Query statement '" + statement.trim()
                        + "' is not supported by floci-gcp. Supported statements are labels.KEY=VALUE, labels.KEY:VALUE, "
                        + "labels.KEY:*, tagKeys, tagValues and tagValueIds with ':' or '=', joined with AND.");
            }
            String field = term.group(1);
            boolean exact = term.group(3).equals("=");
            String value = unquote(term.group(4));
            terms.add(term.group(2) != null ? label(term.group(2), exact, value)
                    : tag(field, exact, value));
        }
        return new AssetQuery(terms);
    }

    boolean matches(SearchResult result) {
        return terms.stream().allMatch(term -> term.test(result));
    }

    private static Predicate<SearchResult> label(String key, boolean exact, String value) {
        return result -> {
            Map<String, String> labels = result.asset().labels();
            String actual = labels == null ? null : labels.get(key);
            if (actual == null) {
                return false;
            }
            if (exact) {
                return actual.equals(value);
            }
            return value.equals("*") || matchesWord(actual, value);
        };
    }

    private static Predicate<SearchResult> tag(String field, boolean exact, String value) {
        return result -> {
            List<String> names = switch (field) {
                case "tagKeys" -> result.tagKeys();
                case "tagValues" -> result.tagValues();
                default -> result.tagValueIds();
            };
            return names.stream().anyMatch(name -> exact ? name.equals(value) : matchesPath(name, value));
        };
    }

    /** {@code :} on a label matches the whole value or, with a trailing {@code *}, its prefix. */
    private static boolean matchesWord(String actual, String value) {
        return value.endsWith("*") ? actual.startsWith(value.substring(0, value.length() - 1)) : actual.equals(value);
    }

    /**
     * {@code :} on a namespaced tag name matches the name, a trailing run of its segments ({@code env/prod}
     * matches {@code 123/env/prod}), or, with a trailing {@code *}, a prefix of either.
     */
    private static boolean matchesPath(String name, String value) {
        boolean prefix = value.endsWith("*");
        String wanted = prefix ? value.substring(0, value.length() - 1) : value;
        if (prefix) {
            return name.startsWith(wanted) || name.contains("/" + wanted);
        }
        return name.equals(wanted) || name.endsWith("/" + wanted);
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                ? value.substring(1, value.length() - 1) : value;
    }
}

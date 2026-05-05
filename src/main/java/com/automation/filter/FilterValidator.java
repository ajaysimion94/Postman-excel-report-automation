package com.automation.filter;

import com.automation.postman.PostmanCollection;
import com.automation.postman.RequestSpec;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public final class FilterValidator {
    private FilterValidator() {
    }

    /**
     * Validates a {@link FilterSpec} against a parsed {@link PostmanCollection}.
     *
     * <p>Checks performed:
     * <ul>
     *   <li>If {@code filter.collection()} is set, it must match the collection's name stem.</li>
     *   <li>Each name in {@code filter.requests()} is checked against the collection's request names;
     *       unknown names produce a warning (not an error) so typos are visible.</li>
     * </ul>
     */
    public static void validate(FilterSpec filter, PostmanCollection collection, Path collectionPath) {
        if (filter == null) {
            return;
        }

        if (filter.outputPrefix() != null && filter.outputPrefix().isBlank()) {
            throw new IllegalArgumentException("Filter outputPrefix cannot be blank.");
        }

        validateAuth(filter.auth());

        if (filter.vars() != null) {
            List<String> blankVarKeys = filter.vars().keySet().stream()
                    .filter(key -> key == null || key.isBlank())
                    .collect(Collectors.toList());
            if (!blankVarKeys.isEmpty()) {
                throw new IllegalArgumentException("Filter vars contains blank variable keys.");
            }
        }

        // Collection name check
        if (filter.collection() != null && !filter.collection().isBlank()) {
            String collectionStem = normalizeCollectionName(collection.name());
            String pathStem = collectionPath == null
                    ? ""
                    : normalizeCollectionName(collectionPath.getFileName().toString().replaceFirst("\\.json$", ""));
            String expected = normalizeCollectionName(filter.collection());
            if (!filter.collection().equalsIgnoreCase(collection.name())
                    && !expected.equals(collectionStem)
                    && !expected.equals(pathStem)) {
                throw new IllegalArgumentException(
                        "Filter targets collection \"" + filter.collection() +
                        "\" but the loaded collection is \"" + collection.name() + "\". " +
                        "Selected file: " + (collectionPath == null ? "<unknown>" : collectionPath.getFileName()) + ".");
            }
        }

        Set<String> available = collection.requests().stream()
                .map(RequestSpec::name)
                .collect(Collectors.toSet());

        // Request name check (strict)
        if (filter.requests() != null && !filter.requests().isEmpty()) {
            List<String> unknown = filter.requests().stream()
                    .filter(name -> !available.contains(name))
                    .collect(Collectors.toList());

            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException(
                        "Filter has unknown request names: " + unknown + ". Available requests: " + available);
            }
        }

        // Validate responseColumns keys map to requests or wildcard
        if (filter.responseColumns() != null && !filter.responseColumns().isEmpty()) {
            List<String> invalidKeys = new ArrayList<>();
            for (Map.Entry<String, List<String>> entry : filter.responseColumns().entrySet()) {
                String key = entry.getKey();
                if (key == null || key.isBlank()) {
                    invalidKeys.add("<blank>");
                    continue;
                }
                if (!"*".equals(key) && !available.contains(key)) {
                    invalidKeys.add(key);
                }
                List<String> columns = entry.getValue();
                if (columns == null || columns.isEmpty()) {
                    throw new IllegalArgumentException(
                            "Filter responseColumns entry for \"" + key + "\" must contain at least one column.");
                }
                if (columns.stream().anyMatch(col -> col == null || col.isBlank())) {
                    throw new IllegalArgumentException(
                            "Filter responseColumns entry for \"" + key + "\" contains blank column names.");
                }
            }
            if (!invalidKeys.isEmpty()) {
                throw new IllegalArgumentException(
                        "Filter responseColumns contains unknown request keys: " + invalidKeys +
                        ". Use request names from the collection or '*' wildcard.");
            }
        }
    }

    private static void validateAuth(FilterAuthSpec auth) {
        if (auth == null) {
            return;
        }
        if (isBlank(auth.apiKey()) ^ isBlank(auth.apiKeyHeader())) {
            throw new IllegalArgumentException(
                    "Filter auth requires both apiKey and apiKeyHeader when using API key authentication.");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String normalizeCollectionName(String value) {
        if (value == null) {
            return "";
        }
        return value
                .toLowerCase()
                .replaceFirst("\\.json$", "")
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }
}
